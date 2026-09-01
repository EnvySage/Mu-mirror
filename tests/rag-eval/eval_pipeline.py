"""
RAG 召回率评测脚本

流程：
  Phase 1: 数据入库 — 登录 → 创建记录 → 确认 → embedding 入库
  Phase 2: 检索评测 — 对测试 query 做向量检索，人工标注计算召回率

用法：
  python eval_pipeline.py populate    # Phase 1: 入库
  python eval_pipeline.py retrieve    # Phase 2: 检索 + 评测
  python eval_pipeline.py all         # 全部执行
"""

import json
import sys
import time
import requests
import psycopg2
from pathlib import Path

# 添加 Python AI 项目路径，用于导入 gRPC 生成的代码
from config import AI_PROJECT_PATH
sys.path.insert(0, AI_PROJECT_PATH)

import grpc
from generated import embedding_pb2 as emb_pb2
from generated import embedding_pb2_grpc as emb_grpc
from generated import common_pb2

from config import (
    API_BASE, USERNAME, PASSWORD,
    DB_CONFIG, EMBEDDING_GRPC_HOST, EMBEDDING_GRPC_PORT,
    EMBEDDING_SOURCE, EMBEDDING_LOCAL_MODEL,
    EMBEDDING_API_PROVIDER, EMBEDDING_API_KEY,
    EMBEDDING_API_MODEL, EMBEDDING_BASE_URL,
    POLL_INTERVAL, POLL_TIMEOUT, TOP_K
)

SCRIPT_DIR = Path(__file__).parent


# ─────────────────────────────────────────────
# Embedding 生成（通过 gRPC 调用 Python 服务）
# ─────────────────────────────────────────────

def generate_embedding(text: str) -> list:
    """通过 gRPC 调用 Python embedding 服务生成向量"""
    with grpc.insecure_channel(f'{EMBEDDING_GRPC_HOST}:{EMBEDDING_GRPC_PORT}') as channel:
        stub = emb_grpc.EmbeddingServiceStub(channel)
        embedding_config = common_pb2.EmbeddingConfig(
            source=EMBEDDING_SOURCE,
            local_model=EMBEDDING_LOCAL_MODEL,
            api_provider=EMBEDDING_API_PROVIDER,
            api_key=EMBEDDING_API_KEY,
            api_model=EMBEDDING_API_MODEL,
            base_url=EMBEDDING_BASE_URL,
        )
        resp = stub.Embed(emb_pb2.EmbedRequest(
            text=text,
            embedding_config=embedding_config
        ))
        return list(resp.vector)


# ─────────────────────────────────────────────
# Phase 1: 数据入库
# ─────────────────────────────────────────────

def login() -> tuple:
    """登录获取 JWT token 和 user_id，返回 (token, user_id)"""
    resp = requests.post(f"{API_BASE}/auth/login", json={
        "username": USERNAME,
        "password": PASSWORD
    })
    resp.raise_for_status()
    data = resp.json()
    inner = data.get("data", data)
    token = inner.get("token")
    user_id = inner.get("user", {}).get("id")
    if not token:
        raise Exception(f"登录失败，响应: {data}")
    if not user_id:
        raise Exception(f"登录响应中没有 user id: {data}")
    print(f"[LOGIN] 登录成功，token: {token[:20]}..., user_id: {user_id}")
    return token, user_id


REQUEST_TIMEOUT = 60  # 单次 HTTP 请求超时（秒）


def create_record(token: str, content: str) -> dict:
    """创建一条记录，返回记录信息"""
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.post(f"{API_BASE}/records", headers=headers, json={
        "content": content
    }, timeout=REQUEST_TIMEOUT)
    resp.raise_for_status()
    return resp.json()


def get_record(token: str, record_id: int) -> dict:
    """查询单条记录状态"""
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.get(f"{API_BASE}/records/{record_id}", headers=headers, timeout=10)
    resp.raise_for_status()
    return resp.json()


def confirm_record(token: str, record_id: int) -> dict:
    """确认审查，触发 embedding"""
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.put(f"{API_BASE}/records/{record_id}/confirm", headers=headers, timeout=REQUEST_TIMEOUT)
    resp.raise_for_status()
    return resp.json()


def wait_for_reviewing(token: str, record_id: int) -> dict:
    """轮询等待记录状态变为 reviewing"""
    start = time.time()
    while time.time() - start < POLL_TIMEOUT:
        result = get_record(token, record_id)
        record = result.get("data", result)
        status = record.get("status", "")
        if status == "reviewing":
            return record
        elif status == "failed":
            raise Exception(f"记录 {record_id} 处理失败: {record}")
        time.sleep(POLL_INTERVAL)
    raise Exception(f"记录 {record_id} 等待超时 ({POLL_TIMEOUT}s)")


def populate_data(resume: bool = False):
    """Phase 1: 将测试数据入库。resume=True 时从上次断点继续"""
    # 加载测试数据
    with open(SCRIPT_DIR / "test_data.json", "r", encoding="utf-8") as f:
        test_data = json.load(f)

    # 断点续跑：加载已有结果
    results = []
    done_contents = set()
    if resume:
        results_path = SCRIPT_DIR / "results" / "populate_results.json"
        if results_path.exists():
            with open(results_path, "r", encoding="utf-8") as f:
                results = json.load(f)
            done_contents = {r["content"] for r in results if r["status"] == "success"}
            print(f"[RESUME] 已完成 {len(done_contents)} 条，从断点继续")
        else:
            print(f"[RESUME] 没有找到历史结果，从头开始")

    remaining = [item for item in test_data if item["content"] not in done_contents]
    print(f"[POPULATE] 待处理 {len(remaining)} 条，共 {len(test_data)} 条")
    print(f"[POPULATE] API: {API_BASE}")
    print()

    if not remaining:
        print("[POPULATE] 所有数据已处理完毕")
        return

    # 登录
    token, _ = login()

    # 逐条处理
    success = len([r for r in results if r["status"] == "success"])
    fail = len([r for r in results if r["status"] in ("error", "create_failed")])

    for i, item in enumerate(remaining, 1):
        content = item["content"]
        topic = item["topic"]
        total_idx = len(done_contents) + i
        print(f"[{total_idx}/{len(test_data)}] [{topic}] {content[:30]}...")

        try:
            # 1. 创建记录
            create_resp = create_record(token, content)
            record = create_resp.get("data", create_resp)
            record_id = record.get("id")
            if not record_id:
                print(f"  ⚠ 创建响应中没有 id: {create_resp}")
                fail += 1
                results.append({"topic": topic, "content": content, "status": "create_failed", "error": str(create_resp)})
                continue
            print(f"  → 创建成功，ID: {record_id}，状态: {record.get('status')}")

            # 2. 等待分类完成（状态变为 reviewing）
            record = wait_for_reviewing(token, record_id)
            print(f"  → 分类完成，状态: {record.get('status')}，标题: {record.get('title', '无')}")

            # 3. 确认审查，触发 embedding
            confirm_resp = confirm_record(token, record_id)
            print(f"  → 确认成功，embedding 已触发")

            success += 1
            results.append({
                "topic": topic,
                "content": content,
                "record_id": record_id,
                "status": "success",
                "title": record.get("title"),
                "content_type": record.get("contentType"),
            })

        except requests.exceptions.Timeout:
            print(f"  ✗ 请求超时（{REQUEST_TIMEOUT}s），跳过")
            fail += 1
            results.append({"topic": topic, "content": content, "status": "timeout", "error": f"请求超时 {REQUEST_TIMEOUT}s"})

        except Exception as e:
            print(f"  ✗ 失败: {e}")
            fail += 1
            results.append({"topic": topic, "content": content, "status": "error", "error": str(e)})

        # 每条处理完立即保存结果（防断电丢数据）
        output_path = SCRIPT_DIR / "results" / "populate_results.json"
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)

        print()

    print("=" * 50)
    print(f"[POPULATE] 完成！成功: {success}, 失败: {fail}, 总计: {len(test_data)}")
    print(f"[POPULATE] 结果已保存到: {output_path}")


# ─────────────────────────────────────────────
# Phase 2: 检索评测
# ─────────────────────────────────────────────

def get_db_connection():
    """获取数据库连接"""
    return psycopg2.connect(**DB_CONFIG)


def search_chunks(conn, user_id: str, query_vector: list, top_k: int = 10) -> list:
    """用 pgvector 做相似度检索"""
    # 将向量转为 PostgreSQL vector 格式: '[0.1,0.2,...]'
    vector_str = "[" + ",".join(str(v) for v in query_vector) + "]"

    with conn.cursor() as cur:
        cur.execute("""
            SELECT c.id, c.record_id, c.content, c.metadata,
                   1 - (c.embedding <=> %s::vector) AS similarity
            FROM chunks c
            WHERE c.user_id = %s::uuid
            ORDER BY c.embedding <=> %s::vector
            LIMIT %s
        """, (vector_str, user_id, vector_str, top_k))

        results = []
        for row in cur.fetchall():
            results.append({
                "chunk_id": row[0],
                "record_id": row[1],
                "content": row[2],
                "metadata": row[3],
                "similarity": float(row[4]),
            })
        return results


def retrieve_and_evaluate():
    """Phase 2: 检索评测"""
    # 加载测试 query
    with open(SCRIPT_DIR / "test_queries.json", "r", encoding="utf-8") as f:
        test_queries = json.load(f)

    # 登录获取 token 和 user_id
    token, user_id = login()
    print(f"[RETRIEVE] 用户 ID: {user_id}")

    # 连接数据库
    conn = get_db_connection()
    print(f"[RETRIEVE] 数据库已连接")

    # 检查 chunks 数量
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM chunks WHERE user_id = %s::uuid", (user_id,))
        chunk_count = cur.fetchone()[0]
    print(f"[RETRIEVE] 用户共有 {chunk_count} 条 chunks")
    if chunk_count == 0:
        print("[RETRIEVE] ⚠ 没有数据，请先运行 populate 命令")
        conn.close()
        return

    # 预热 embedding 服务（第一次调用会加载模型）
    print("[RETRIEVE] 预热 embedding 服务...")
    try:
        generate_embedding("预热测试")
        print("[RETRIEVE] embedding 服务就绪")
    except Exception as e:
        print(f"[RETRIEVE] ⚠ embedding 服务连接失败: {e}")
        conn.close()
        return

    print()

    # 逐条测试
    all_results = []
    for i, tq in enumerate(test_queries, 1):
        query = tq["query"]
        expected_topics = tq["expected_topics"]
        print(f"[{i}/{len(test_queries)}] Query: {query}")
        print(f"  预期主题: {expected_topics}")

        try:
            # 生成 query 的 embedding
            query_vector = generate_embedding(query)

            # 检索 top-K
            results = search_chunks(conn, user_id, query_vector, TOP_K)

            # 显示结果
            print(f"  检索到 {len(results)} 条:")
            for j, r in enumerate(results, 1):
                sim = r['similarity']
                content_preview = r['content'][:50]
                marker = "✓" if sim > 0.5 else "~"  # 粗略标记
                print(f"    #{j} [sim={sim:.4f} {marker}] {content_preview}...")

            all_results.append({
                "query": query,
                "expected_topics": expected_topics,
                "results": results,
            })

        except Exception as e:
            print(f"  ✗ 检索失败: {e}")
            all_results.append({
                "query": query,
                "expected_topics": expected_topics,
                "results": [],
                "error": str(e),
            })

        print()

    conn.close()

    # 保存检索结果
    output_path = SCRIPT_DIR / "results" / "retrieve_results.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2, default=str)

    print(f"[RETRIEVE] 结果已保存到: {output_path}")
    print()
    print("下一步: 打开 results/retrieve_results.json，人工标注每条结果是否相关")
    print("然后运行: python eval_pipeline.py calculate  计算 Recall@K 和 MRR")


# ─────────────────────────────────────────────
# 入口
# ─────────────────────────────────────────────

def main():
    if len(sys.argv) < 2:
        print("用法:")
        print("  python eval_pipeline.py populate    # Phase 1: 数据入库（从头开始）")
        print("  python eval_pipeline.py resume      # Phase 1: 从断点继续入库")
        print("  python eval_pipeline.py retrieve    # Phase 2: 检索评测")
        print("  python eval_pipeline.py all         # 全部执行")
        return

    cmd = sys.argv[1].lower()

    if cmd == "populate":
        populate_data(resume=False)
    elif cmd == "resume":
        populate_data(resume=True)
    elif cmd == "retrieve":
        retrieve_and_evaluate()
    elif cmd == "all":
        populate_data(resume=False)
        print()
        retrieve_and_evaluate()
    else:
        print(f"未知命令: {cmd}")
        print("可用命令: populate, resume, retrieve, all")


if __name__ == "__main__":
    main()

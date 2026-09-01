"""
交互式标注工具

对每条 query 的检索结果，人工标注是否相关（1=相关，0=不相关）
标注结果保存到 results/annotations.json

用法：
  python annotate.py
"""

import json
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
RESULTS_PATH = SCRIPT_DIR / "results" / "retrieve_results.json"
ANNOTATIONS_PATH = SCRIPT_DIR / "results" / "annotations.json"


def load_existing_annotations() -> dict:
    """加载已有标注（支持断点续标）"""
    if ANNOTATIONS_PATH.exists():
        with open(ANNOTATIONS_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_annotations(annotations: dict):
    """保存标注结果"""
    with open(ANNOTATIONS_PATH, "w", encoding="utf-8") as f:
        json.dump(annotations, f, ensure_ascii=False, indent=2)


def main():
    # 加载检索结果
    with open(RESULTS_PATH, "r", encoding="utf-8") as f:
        retrieve_results = json.load(f)

    # 加载已有标注
    annotations = load_existing_annotations()
    annotated_queries = set(annotations.keys())

    # 统计
    total_queries = len(retrieve_results)
    done_count = len(annotated_queries & {r["query"] for r in retrieve_results})

    print("=" * 60)
    print("RAG 检索结果标注工具")
    print("=" * 60)
    print(f"共 {total_queries} 条 query，已标注 {done_count} 条")
    print()
    print("操作说明：")
    print("  1 = 相关（这条结果确实回答了 query）")
    print("  0 = 不相关（这条结果和 query 无关）")
    print("  s = 跳过这条 query")
    print("  q = 退出标注（已标注的内容会保存）")
    print()

    for i, item in enumerate(retrieve_results, 1):
        query = item["query"]
        expected = item["expected_topics"]
        results = item.get("results", [])[:5]  # 只标 top-5，减少工作量

        # 跳过已标注的
        if query in annotated_queries:
            continue

        print(f"\n{'=' * 60}")
        print(f"[{i}/{total_queries}] Query: {query}")
        print(f"预期主题: {expected}")
        print(f"检索到 {len(results)} 条结果：")
        print()

        if not results:
            print("  （无结果，自动跳过）")
            continue

        query_annotations = []
        for j, r in enumerate(results, 1):
            content = r["content"]
            sim = r["similarity"]
            content_type = r.get("metadata", {}).get("contentType", "未知")
            title = r.get("metadata", {}).get("title", "无标题")

            print(f"  --- 结果 #{j} ---")
            print(f"  标题: {title}")
            print(f"  类型: {content_type}")
            print(f"  相似度: {sim:.4f}")
            print(f"  内容: {content}")
            print()

            while True:
                label = input(f"  相关? (1/0/s跳过query/q退出): ").strip().lower()
                if label == "q":
                    save_annotations(annotations)
                    print(f"\n已保存标注到 {ANNOTATIONS_PATH}")
                    return
                elif label == "s":
                    print(f"  跳过 query: {query}")
                    break
                elif label in ("1", "0"):
                    query_annotations.append({
                        "chunk_id": r["chunk_id"],
                        "record_id": r["record_id"],
                        "content": content,
                        "similarity": sim,
                        "relevant": int(label),
                    })
                    break
                else:
                    print("  请输入 1、0、s 或 q")

        if query_annotations:
            annotations[query] = {
                "expected_topics": expected,
                "annotations": query_annotations,
            }
            # 每条 query 标完就保存
            save_annotations(annotations)

    print(f"\n{'=' * 60}")
    print(f"标注完成！结果已保存到 {ANNOTATIONS_PATH}")
    print(f"共标注 {len(annotations)} 条 query")
    print(f"\n下一步: python calculate.py  计算 Recall@K 和 MRR")


if __name__ == "__main__":
    main()

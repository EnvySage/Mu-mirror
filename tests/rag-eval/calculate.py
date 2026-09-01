"""
计算 Recall@K 和 MRR 指标

从 annotations.json 读取人工标注，计算：
  - Recall@5: top-5 结果中相关结果的比例
  - Recall@10: top-10 结果中相关结果的比例
  - MRR: 第一个相关结果的排名倒数均值

用法：
  python calculate.py
"""

import json
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
ANNOTATIONS_PATH = SCRIPT_DIR / "results" / "annotations.json"


def calculate_metrics(annotations: dict, k_values: list = [3, 5, 10]):
    """计算各项指标"""
    total_queries = len(annotations)
    if total_queries == 0:
        print("没有标注数据，请先运行 annotate.py")
        return

    recall_at_k = {k: 0 for k in k_values}
    mrr_sum = 0
    total_relevant = 0
    details = []

    for query, data in annotations.items():
        anns = data["annotations"]
        relevant_count = sum(1 for a in anns if a["relevant"] == 1)
        total_relevant += relevant_count

        # Recall@K
        for k in k_values:
            top_k = anns[:k]
            hits_in_k = sum(1 for a in top_k if a["relevant"] == 1)
            if relevant_count > 0:
                recall_at_k[k] += hits_in_k / relevant_count

        # MRR（第一个相关结果的排名倒数）
        rr = 0
        for rank, a in enumerate(anns, 1):
            if a["relevant"] == 1:
                rr = 1.0 / rank
                break
        mrr_sum += rr

        details.append({
            "query": query,
            "relevant_count": relevant_count,
            "total_results": len(anns),
            "first_relevant_rank": next(
                (rank for rank, a in enumerate(anns, 1) if a["relevant"] == 1), None
            ),
            "rr": rr,
        })

    # 输出结果
    print("=" * 60)
    print("RAG 召回率评测结果")
    print("=" * 60)
    print(f"评测 query 数: {total_queries}")
    print(f"总相关结果数: {total_relevant}")
    print()

    print("【整体指标】")
    for k in k_values:
        avg_recall = recall_at_k[k] / total_queries
        print(f"  Recall@{k}: {avg_recall:.4f} ({avg_recall * 100:.1f}%)")

    avg_mrr = mrr_sum / total_queries
    print(f"  MRR:       {avg_mrr:.4f} ({avg_mrr * 100:.1f}%)")
    print()

    print("【逐条详情】")
    print(f"{'Query':<25} {'相关数':>6} {'首个排名':>8} {'RR':>8}")
    print("-" * 55)
    for d in details:
        first_rank = d["first_relevant_rank"] or "无"
        print(f"{d['query']:<25} {d['relevant_count']:>6} {str(first_rank):>8} {d['rr']:>8.4f}")

    # 保存结果
    output = {
        "summary": {
            "total_queries": total_queries,
            "total_relevant": total_relevant,
            **{f"recall@{k}": recall_at_k[k] / total_queries for k in k_values},
            "mrr": avg_mrr,
        },
        "details": details,
    }
    output_path = SCRIPT_DIR / "results" / "metrics.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"\n详细结果已保存到: {output_path}")


def main():
    if not ANNOTATIONS_PATH.exists():
        print(f"找不到标注文件: {ANNOTATIONS_PATH}")
        print("请先运行: python annotate.py")
        return

    with open(ANNOTATIONS_PATH, "r", encoding="utf-8") as f:
        annotations = json.load(f)

    calculate_metrics(annotations)


if __name__ == "__main__":
    main()

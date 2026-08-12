#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EvoTrace 压测结果汇总报告生成器（report.py）
=================================================================
读取 seed.py 的 --json 输出（或 ./results/*.json），汇总吞吐/延迟/错误率，
并与 thresholds.yaml 阈值比对，输出逐项 PASS / FAIL 结果表，并生成 result.md。

用法示例：
  python3 scripts/bench/seed.py --api-key K --api-secret S --json results/dev.json
  python3 scripts/bench/report.py --input results/dev.json --env dev
  python3 scripts/bench/report.py --dir results --env staging \
      --output results/result.md
"""

import argparse
import glob
import json
import os
from datetime import datetime

# ---------------------------------------------------------------------------
# 阈值指标到结果字段的映射（result 字段名 -> thresholds.yaml 中的路径）
# ---------------------------------------------------------------------------
# 每个条目为一个 (指标名, 类型, 阈值路径, 结果取值函数)
#   类型：'gt' 表示下限（result >= threshold 即 PASS）
#         'lt' 表示上限（result <= threshold 即 PASS）
METRIC_DEFS = [
    # (显示名, 结果字段, 阈值映射函数, 阈值路径前缀, 比较类型, 单位)
    ("ingest.throughput.eventsPerSec", "throughputEventsPerSec",
     "ingest.throughput.eventsPerSec.min", "gt", "events/s"),
    ("ingest.p95Ms", "p95Ms",
     "ingest.p95Ms.max", "lt", "ms"),
    ("webhookToVisibleMs", "webhookKpi",
     "webhookToVisibleMs.max", "lt", "ms"),
    ("compare.p95Ms", "compareP95Ms",
     "compare.p95Ms.max", "lt", "ms"),
    ("timeline.p95Ms", "timelineP95Ms",
     "timeline.p95Ms.max", "lt", "ms"),
]


def load_yaml_simple(path):
    """无 PyYAML 时的简易解析：按缩进层级构建嵌套 dict。
    仅支持本 thresholds.yaml / profiles.yaml 的缩进结构（key: 与 key: value）。"""
    root = {}
    # stack：每层 (缩进, 该层 dict)
    stack = [(-1, root)]
    with open(path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.split("#", 1)[0].rstrip()  # 去掉行内注释
            if not line.strip():
                continue
            indent = len(line) - len(line.lstrip())
            content = line.strip()
            if ":" not in content:
                continue
            key, _, val = content.partition(":")
            key = key.strip()
            val = val.strip()
            # 弹出深度大于等于当前缩进的所有层
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if val == "":
                # 新的子 map
                node = {}
                stack[-1][1][key] = node
                stack.append((indent, node))
            else:
                # 标量叶子
                try:
                    node = float(val)
                except ValueError:
                    node = val
                stack[-1][1][key] = node
    return root


def get_threshold(thresholds, env, path):
    """从 thresholds[env] 中按路径取阈值，如 'ingest.throughput.eventsPerSec.min'。"""
    node = thresholds.get(env, {})
    for part in path.split("."):
        if not isinstance(node, dict):
            return None
        node = node.get(part)
    return node


def load_result(path):
    """加载单个结果 JSON 文件。"""
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def evaluate(result, thresholds, env):
    """将单个结果与阈值比对，返回 [(显示名, 实测, 阈值, 单位, PASS/FAIL/NA)]。"""
    rows = []
    for disp, res_field, thr_path, cmp_type, unit in METRIC_DEFS:
        actual = result.get(res_field)
        threshold = get_threshold(thresholds, env, thr_path)
        if actual is None or threshold is None:
            rows.append((disp, actual, threshold, unit, "NA"))
            continue
        passed = (actual >= threshold) if cmp_type == "gt" else (actual <= threshold)
        rows.append((disp, actual, threshold, unit, "PASS" if passed else "FAIL"))
    return rows


def render_markdown(env, results, rows_list, output_path):
    """渲染 result.md 报告。"""
    lines = []
    lines.append("# EvoTrace 性能压测报告\n")
    lines.append("- 生成时间：%s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    lines.append("- 压测环境：`%s`" % env)
    lines.append("- 结果文件：%d 份\n" % len(results))

    for (path, rows) in rows_list:
        lines.append("## 结果文件：`%s`\n" % os.path.basename(path))
        lines.append("| 指标 | 实测值 | 阈值 | 单位 | 判定 |")
        lines.append("| --- | --- | --- | --- | --- |")
        for disp, actual, threshold, unit, verdict in rows:
            lines.append("| %s | %s | %s | %s | %s |"
                         % (disp, actual, threshold, unit, verdict))
        lines.append("")

    # 汇总是否全部通过
    all_pass = all(v == "PASS" for _, rows in rows_list for (_, _, _, _, v) in rows)
    lines.append("## 总体结论\n")
    lines.append("- **全部通过**" if all_pass else "- **存在未达标项**")
    lines.append("\n> 阈值基线来自 `thresholds.yaml`，如需调整请修改该文件后重跑。")

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return all_pass


def print_table(rows):
    """打印逐项结果表到 stdout。"""
    print("\n逐项结果（环境阈值比对）：")
    print("%-32s %12s %12s %8s %6s" % ("指标", "实测值", "阈值", "单位", "判定"))
    print("-" * 78)
    for disp, actual, threshold, unit, verdict in rows:
        av = "-" if actual is None else actual
        tv = "-" if threshold is None else threshold
        print("%-32s %12s %12s %8s %6s" % (disp, av, tv, unit, verdict))


def collect_inputs(args):
    """收集输入文件列表：--input 或 --dir。"""
    if args.input:
        return args.input
    if args.dir:
        pattern = os.path.join(args.dir, "*.json")
        files = sorted(glob.glob(pattern))
        if not files:
            raise SystemExit("目录 %s 下没有找到 *.json 结果文件" % args.dir)
        return files
    raise SystemExit("请通过 --input 或 --dir 指定结果文件")


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="EvoTrace 压测结果汇总：读取 seed.py 的 JSON 输出，"
                    "与 thresholds.yaml 比对并生成 result.md。",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--input", nargs="+", default=None,
                        help="一个或多个结果 JSON 文件")
    parser.add_argument("--dir", default=None,
                        help="结果目录（读取该目录下所有 *.json）")
    parser.add_argument("--env", default="dev",
                        help="压测环境（dev/staging/prod），用于取阈值")
    parser.add_argument("--thresholds", default=None,
                        help="自定义阈值文件路径（默认取同目录 thresholds.yaml）")
    parser.add_argument("--output", default="results/result.md",
                        help="生成的报告文件路径")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)

    # 阈值文件：默认取同目录 thresholds.yaml
    thr_path = args.thresholds or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "thresholds.yaml")
    if not os.path.exists(thr_path):
        raise SystemExit("阈值文件不存在：%s" % thr_path)
    thresholds = load_yaml_simple(thr_path)
    if args.env not in thresholds:
        print("警告：环境 [%s] 未在 thresholds.yaml 中找到，切换到 dev。" % args.env)
        env = "dev"
    else:
        env = args.env

    # 收集并逐一评估
    input_files = collect_inputs(args)
    results = []
    rows_list = []
    for path in input_files:
        result = load_result(path)
        rows = evaluate(result, thresholds, env)
        results.append(result)
        rows_list.append((path, rows))
        print_table(rows)

    # 生成 markdown 报告
    all_pass = render_markdown(env, results, rows_list, args.output)
    print("\n报告已生成：%s" % args.output)

    return 0 if all_pass else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
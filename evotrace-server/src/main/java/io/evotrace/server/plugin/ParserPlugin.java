package io.evotrace.server.plugin;

import java.util.List;

/**
 * 解析器插件 SPI（V2.5）。
 * <p>
 * 把"代码/接口/DDL/配置/依赖"解析从硬编码演进为可扩展插件。
 * 第三方通过实现本接口并打 Jar 包，即可被 {@link ParserPluginRegistry} 热加载。
 */
public interface ParserPlugin {

    /** 输出项类别。 */
    enum Category {
        CODE, API, DDL, CONFIG, DEPENDENCY
    }

    /** 插件唯一标识，如 "vendor.parser.git-java"。 */
    String id();

    /** 插件名称。 */
    String name();

    /** 支持的解析类别。 */
    Category category();

    /** 插件版本号。 */
    int version();

    /**
     * 解析输入物（blob / 快照 / 仓库路径），产出归一化清单项。
     *
     * @param input  待解析内容（原始文本或文件路径）
     * @param feature 特征名/文件名，用于定位
     * @return 归一化清单项
     */
    List<ParseItem> parse(String input, String feature);

    /**
     * 归一化解析结果项。
     *
     * @param type    类型（如 CLASS / METHOD / TABLE / PARAM / DEPENDENCY）
     * @param name    名称
     * @param detail  附加信息
     */
    record ParseItem(String type, String name, String detail) {
    }
}
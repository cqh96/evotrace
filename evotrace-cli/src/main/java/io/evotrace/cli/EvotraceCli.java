package io.evotrace.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "evotrace",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "Scan a repository (Go/Python/Vue/Node) and report its inventory to EvoTrace.")
public class EvotraceCli implements Callable<Integer> {

    @Option(names = "--lang", description = "Language: auto|go|python|node|vue", defaultValue = "auto")
    private String lang;

    @Option(names = "--project-key", required = true, description = "EvoTrace project key")
    private String projectKey;

    @Option(names = "--app-key", description = "EvoTrace application key")
    private String appKey;

    @Option(names = "--api-key", required = true, description = "Ingestion API key")
    private String apiKey;

    @Option(names = "--server", description = "EvoTrace server url", defaultValue = "http://localhost:8080")
    private String server;

    @Option(names = "--version-tag", description = "Version/tag this scan belongs to")
    private String versionTag;

    public static void main(String[] args) {
        System.exit(new CommandLine(new EvotraceCli()).execute(args));
    }

    @Override
    public Integer call() {
        // TODO(M2): LanguageAnalyzer SPI (go.mod / requirements.txt / package.json parsers,
        //           gin/FastAPI route extraction) -> INVENTORY_REPORT envelope -> POST /open-api/v1/events
        System.out.printf("scan lang=%s project=%s server=%s%n", lang, projectKey, server);
        return 0;
    }
}

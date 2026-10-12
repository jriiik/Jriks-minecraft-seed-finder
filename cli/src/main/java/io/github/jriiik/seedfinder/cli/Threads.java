package io.github.jriiik.seedfinder.cli;

import picocli.CommandLine;

/** The {@code --threads} value of the search commands. */
final class Threads {

    private static final int MAX = 1024;

    private Threads() {
    }

    /** 0 (the default) means all CPU threads. */
    static int resolve(CommandLine commandLine, int requested) {
        if (requested < 0 || requested > MAX) {
            throw new CommandLine.ParameterException(commandLine, "--threads must be between 1 and " + MAX + " (or left out to use all CPU threads)");
        }
        return requested > 0 ? requested : Runtime.getRuntime().availableProcessors();
    }
}

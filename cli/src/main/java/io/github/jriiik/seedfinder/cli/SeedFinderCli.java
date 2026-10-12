package io.github.jriiik.seedfinder.cli;

import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the {@code seedfinder} command-line app. */
@Command(
    name = "seedfinder",
    mixinStandardHelpOptions = true,
    versionProvider = SeedFinderCli.AppVersion.class,
    description = {
        "Jrik's Seed Finder for Minecraft (Java Edition).",
        "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
    },
    subcommands = {
        FindCommand.class, StructureSeedCommand.class, LocateCommand.class, StructuresCommand.class, VersionsCommand.class,
        CommandLine.HelpCommand.class,
    },
    footer = {
        "",
        "Examples:",
        "  seedfinder find desert_pyramid:272,80 swamp_hut:256,-144 village:336,80 shipwreck:-128,32 ruined_portal:16,0 trial_chamber:0,224",
        "  seedfinder locate --seed 12345 villages desert_pyramids",
    }
)
public final class SeedFinderCli implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new SeedFinderCli()).setCaseInsensitiveEnumValuesAllowed(true);
        commandLine.setParameterExceptionHandler(SeedFinderCli::printShortError);
        return commandLine;
    }

    /** Prints just the problem and a hint, instead of the whole help page. */
    private static int printShortError(CommandLine.ParameterException problem, String[] args) {
        CommandLine failed = problem.getCommandLine();
        PrintWriter err = failed.getErr();
        err.println(failed.getColorScheme().errorText(problem.getMessage()));
        CommandLine.UnmatchedArgumentException.printSuggestions(problem, err);
        err.println("Run '" + failed.getCommandSpec().qualifiedName() + " --help' to see how to use it.");
        return failed.getCommandSpec().exitCodeOnInvalidInput();
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static final class AppVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = SeedFinderCli.class.getPackage().getImplementationVersion();
            return new String[] {"Jrik's Seed Finder " + (version != null ? version : "(development build)")};
        }
    }
}

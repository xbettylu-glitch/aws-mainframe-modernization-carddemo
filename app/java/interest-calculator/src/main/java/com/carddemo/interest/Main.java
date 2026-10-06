package com.carddemo.interest;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Command-line entry point replacing JCL {@code INTCALC} step {@code STEP15}.
 *
 * <pre>
 * java -jar interest-calculator.jar --parm 2022071800 \
 *     --tcatbal tcatbal.txt --xref cardxref.txt --acct acctdata.txt \
 *     --discgrp discgrp.txt --transact transact.dat [--timestamp 2022-07-18T01:02:03.45]
 * </pre>
 *
 * The exit status is 0 on success and 999 (seen by the shell as 231) on an abend.
 * {@code --timestamp} freezes {@code FUNCTION CURRENT-DATE}, for reproducible runs.
 */
public final class Main {

    private static final List<String> REQUIRED = List.of("parm", "tcatbal", "xref", "acct", "discgrp", "transact");

    private Main() {
    }

    public static void main(String[] args) {
        PrintStream sysout = new PrintStream(new FileOutputStream(FileDescriptor.out), false,
                StandardCharsets.ISO_8859_1);
        System.exit(run(args, sysout));
    }

    static int run(String[] args, PrintStream sysout) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                return usage("unexpected argument: " + args[i]);
            }
            options.put(args[i].substring(2), args[++i]);
        }
        for (String name : REQUIRED) {
            if (!options.containsKey(name)) {
                return usage("missing --" + name);
            }
        }
        Supplier<LocalDateTime> clock = LocalDateTime::now;
        if (options.containsKey("timestamp")) {
            LocalDateTime fixed = LocalDateTime.parse(options.get("timestamp"));
            clock = () -> fixed;
        }
        JobFiles files = new JobFiles(Path.of(options.get("tcatbal")), Path.of(options.get("xref")),
                Path.of(options.get("acct")), Path.of(options.get("discgrp")), Path.of(options.get("transact")));
        return new InterestCalculator(files, options.get("parm"), clock, sysout).run();
    }

    private static int usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: --parm <date> --tcatbal <f> --xref <f> --acct <f> --discgrp <f> --transact <f>"
                + " [--timestamp <yyyy-MM-ddTHH:mm:ss.SS>]");
        return 2;
    }
}

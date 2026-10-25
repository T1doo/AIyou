package com.t1doo.aiyou.recoveryprobe;

import java.io.File;

/** Host executable for the production probe's deterministic ZIP verifier only. */
public final class ExportHostProbe {
    public static void main(String[] args) throws Exception {
        ExportCheck.verify(new File(args[0]), args[1], args[2], args[3], args[4]);
        System.out.println("PASS: actual ExportCheck accepted the controlled ZIP; no Android UI ran");
    }
}

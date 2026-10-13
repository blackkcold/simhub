package com.blackkcold.simhub;
/** Local, user-authorized Shizuku laboratory only; not exposed to Relay commands. */
interface IShizukuShell {
    String run(String command);
}

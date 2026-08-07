# TermuxMod Builder (R8/ProGuard rules for :builder-core)
# Semua class builder dipakai langsung dari :app — jangan diobfuscate nama method
# yang dipanggil via reflection dari termux-shared.

-keep class com.termux.builder.** { *; }
-dontwarn com.termux.builder.**

# AppShell / ExecutionCommand dipakai lintas module
-keep class com.termux.shared.shell.command.** { *; }
-keep class com.termux.shared.shell.StreamGobbler { *; }

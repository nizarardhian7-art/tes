     .global blob
     .global blob_size
     .section .rodata
 blob:
 #if defined __aarch64__
     .incbin "bootstrap-aarch64.zip"
 #else
 # error "TermuxMod is arm64-v8a (aarch64) only. See CHANGES-TERMUXMOD.md."
 #endif
 1:
 blob_size:
     .int 1b - blob

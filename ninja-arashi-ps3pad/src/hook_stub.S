/*
 * hook_stub.S -- trampoline entry for the NinjaMovementScript::Update hook.
 *
 *   installed as the first instructions of Update (see ps3pad.c);  x0 holds
 *   the NinjaMovementScript instance.  We preserve the entire argument
 *   register file, call the C handler, restore, and continue either in the
 *   relocated original instructions (inline hook) or in the untouched
 *   original function (MethodInfo swap fallback).
 */
#if defined(__aarch64__)
    .text
    .global ps3pad_stub
    .hidden ps3pad_stub
    .type   ps3pad_stub, %function
    .p2align 2
ps3pad_stub:
    sub  sp, sp, #256
    stp  x0, x1, [sp, #0]
    stp  x2, x3, [sp, #16]
    stp  x4, x5, [sp, #32]
    stp  x6, x7, [sp, #48]
    stp  x8, x9, [sp, #64]
    stp  x10, x11, [sp, #80]
    stp  x12, x13, [sp, #96]
    stp  x14, x15, [sp, #112]
    stp  x16, x17, [sp, #128]
    stp  x18, x29, [sp, #144]
    str  x30, [sp, #160]
    stp  d0, d1, [sp, #176]
    stp  d2, d3, [sp, #192]
    stp  d4, d5, [sp, #208]
    stp  d6, d7, [sp, #224]
    bl   ps3pad_on_update
    ldp  d6, d7, [sp, #224]
    ldp  d4, d5, [sp, #208]
    ldp  d2, d3, [sp, #192]
    ldp  d0, d1, [sp, #176]
    ldr  x30, [sp, #160]
    ldp  x18, x29, [sp, #144]
    ldp  x16, x17, [sp, #128]
    ldp  x14, x15, [sp, #112]
    ldp  x12, x13, [sp, #96]
    ldp  x10, x11, [sp, #80]
    ldp  x8, x9, [sp, #64]
    ldp  x6, x7, [sp, #48]
    ldp  x4, x5, [sp, #32]
    ldp  x2, x3, [sp, #16]
    ldp  x0, x1, [sp, #0]
    add  sp, sp, #256
    adrp x16, ps3pad_tramp
    ldr  x16, [x16, :lo12:ps3pad_tramp]
    br   x16
    .size ps3pad_stub, . - ps3pad_stub
#endif

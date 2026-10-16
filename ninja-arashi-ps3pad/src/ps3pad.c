/*
 * ps3pad.c -- DualShock 3 / Sixaxis (PS3 controller) support for "Ninja Arashi"
 *             Unity 2018.3 IL2CPP, package com.blackpanther.ninjaarashi
 *
 * Built as libps3pad.so, loaded through a DT_NEEDED entry added to libmain.so.
 * Freestanding build: no libc is linked, every libc symbol below is resolved by
 * the Android linker (bionic) at load time, exactly as any app .so does.
 *
 * flow:
 *   1. ELF constructor -> dlopen liblog, start the watcher thread
 *   2. watcher waits for libil2cpp.so, binds the IL2CPP C API
 *   3. waits for Assembly-CSharp.dll (il2cpp_init finished), finds
 *      NinjaMovementScript and its zero-argument control entry points
 *   4. hooks NinjaMovementScript::Update, which supplies the live instance on
 *      the Unity main thread once per frame
 *   5. reads the pad through Unity's own legacy Input API (the shipped
 *      InputManager maps Horizontal/Vertical to joystick axis 0/1 and
 *      Fire1..3/Jump to joystick buttons 0..3)
 *   6. replays the pad state through the game's own Button_* handlers, i.e.
 *      the same code path the on-screen touch buttons use
 */

typedef unsigned char  u8;
typedef unsigned short u16;
typedef unsigned int   u32;
typedef unsigned long long u64;
typedef unsigned long  usize;
typedef long           ssize;

/* --------------------------------------------------------------- bionic */
extern void*  dlopen(const char*, int);
extern void*  dlsym(void*, const char*);
extern int    dlclose(void*);
extern void*  mmap(void*, usize, int, int, int, long);
extern int    mprotect(void*, usize, int);
extern int    pthread_create(unsigned long*, const void*, void* (*)(void*), void*);
extern int    usleep(unsigned int);
extern int    write(int, const void*, usize);
extern int    open(const char*, int, ...);
extern int    mkdir(const char*, unsigned int);
extern void*  memcpy(void*, const void*, usize);
extern int    strcmp(const char*, const char*);
extern void*  fopen(const char*, const char*);
extern usize  fread(void*, usize, usize, void*);
extern int    fclose(void*);
extern int    __android_log_write(int, const char*, const char*);
extern char*  strstr(const char*, const char*);

#define RTLD_NOW    2
#define RTLD_NOLOAD 4

#define PROT_READ     1
#define PROT_WRITE    2
#define PROT_EXEC     4
#define MAP_PRIVATE   2
#define MAP_ANONYMOUS 0x20

/* ---- cache maintenance for freshly written code ----------------------- */
#if defined(__aarch64__)
static void cache_flush(void* p0, void* p1)
{
    u64 ctr;
    unsigned long dline, iline, a;
    __asm__ __volatile__("mrs %0, ctr_el0" : "=r"(ctr));
    dline = 4UL << ((ctr >> 16) & 0xf);
    iline = 4UL << (ctr & 0xf);
    for (a = (unsigned long)p0 & ~(dline - 1); a < (unsigned long)p1; a += dline)
        __asm__ __volatile__("dc cvau, %0" :: "r"(a) : "memory");
    __asm__ __volatile__("dsb ish" ::: "memory");
    for (a = (unsigned long)p0 & ~(iline - 1); a < (unsigned long)p1; a += iline)
        __asm__ __volatile__("ic ivau, %0" :: "r"(a) : "memory");
    __asm__ __volatile__("dsb ish\nisb\n" ::: "memory");
}
#else
static void cache_flush(void* p0, void* p1)
{
    (void)p0; (void)p1;
    __asm__ __volatile__("dsb\nisb\n" ::: "memory");
}
#endif

/* -------------------------------------------------------------- logging */
static int    g_log_fd = -1;
static int    g_log_tried;
static char   g_logbuf[640];
static int  (*p_log_write)(int, const char*, const char*);

static void log_open(void)
{
    static const char* files[] = {
        "/sdcard/Android/data/com.blackpanther.ninjaarashi/files/ps3pad.log",
        "/storage/emulated/0/Android/data/com.blackpanther.ninjaarashi/files/ps3pad.log",
        "/sdcard/ps3pad.log",
        "/data/local/tmp/ps3pad.log",
        0
    };
    static const char* dirs[] = {
        "/sdcard/Android/data/com.blackpanther.ninjaarashi",
        "/sdcard/Android/data/com.blackpanther.ninjaarashi/files",
        "/sdcard/Android/data",
        "/sdcard/Android",
        0
    };
    int i, pass;
    if (g_log_tried) return;
    g_log_tried = 1;
    for (pass = 0; pass < 2; pass++) {
        for (i = 0; files[i]; i++) {
            int fd = open(files[i], 1 /*O_WRONLY*/ | 0x400 /*O_APPEND*/ | 0x40 /*O_CREAT*/, 0644);
            if (fd >= 0) {
                g_log_fd = fd;
                write(fd, "\n=== ps3pad: DualShock 3 mod ===\n", 34);
                return;
            }
        }
        if (pass == 0) for (i = 0; dirs[i]; i++) mkdir(dirs[i], 0777);
    }
}

static void logcat_open(void)
{
    void* h = dlopen("liblog.so", RTLD_NOW);
    if (h)
        p_log_write = (int (*)(int, const char*, const char*))dlsym(h, "__android_log_write");
}

/* dladdr lives in libdl; resolved lazily so a device without it can never
   stop this library from loading */
static int (*f_dladdr)(const void*, void*);

static void dladdr_open(void)
{
    void* h = dlopen("libdl.so", RTLD_NOW);
    if (h) f_dladdr = (int (*)(const void*, void*))dlsym(h, "dladdr");
}

static void put(const char* s, int n)
{
    if (g_log_fd < 0 && !g_log_tried) log_open();
    if (g_log_fd >= 0) write(g_log_fd, s, (usize)n);
    if (p_log_write) p_log_write(4, "ps3pad", s);
}

static void log_str(const char* s)
{
    int i = 0;
    while (s[i] && i < 560) { g_logbuf[i] = s[i]; i++; }
    g_logbuf[i++] = '\n';
    put(g_logbuf, i);
}

static void log_two(const char* a, const char* b)
{
    int i = 0, j = 0;
    while (a[i] && i < 260) { g_logbuf[i] = a[i]; i++; }
    while (b[j] && i < 560) { g_logbuf[i++] = b[j++]; }
    g_logbuf[i++] = '\n';
    put(g_logbuf, i);
}

static void log_i(const char* s, int v)
{
    int i = 0, n = 0, neg = 0;
    char tmp[12];
    while (s[i] && i < 260) { g_logbuf[i] = s[i]; i++; }
    if (v < 0) { neg = 1; v = -v; }
    if (v == 0) tmp[n++] = '0';
    while (v > 0) { tmp[n++] = (char)('0' + v % 10); v /= 10; }
    if (neg) g_logbuf[i++] = '-';
    while (n > 0) g_logbuf[i++] = tmp[--n];
    g_logbuf[i++] = '\n';
    put(g_logbuf, i);
}

static void log_hex(const char* s, unsigned long long v)
{
    static const char* hx = "0123456789abcdef";
    int i = 0, j;
    while (s[i] && i < 260) { g_logbuf[i] = s[i]; i++; }
    g_logbuf[i++] = '0'; g_logbuf[i++] = 'x';
    for (j = 15; j >= 0; j--) g_logbuf[i++] = hx[(v >> (j * 4)) & 0xf];
    g_logbuf[i++] = '\n';
    put(g_logbuf, i);
}

static void log_f(const char* s, float f)
{
    int i = 0, v, frac, n = 0, neg = 0;
    char tmp[12];
    while (s[i] && i < 260) { g_logbuf[i] = s[i]; i++; }
    if (f < 0.0f) { neg = 1; f = -f; }
    if (f > 100.0f) f = 100.0f;
    v = (int)f;
    frac = (int)((f - (float)v) * 1000.0f);
    if (frac > 999) frac = 999;
    if (neg) g_logbuf[i++] = '-';
    if (v == 0) tmp[n++] = '0';
    while (v > 0) { tmp[n++] = (char)('0' + v % 10); v /= 10; }
    while (n > 0) g_logbuf[i++] = tmp[--n];
    g_logbuf[i++] = '.';
    g_logbuf[i++] = (char)('0' + (frac / 100) % 10);
    g_logbuf[i++] = (char)('0' + (frac / 10) % 10);
    g_logbuf[i++] = (char)('0' + frac % 10);
    g_logbuf[i++] = '\n';
    put(g_logbuf, i);
}

/* ------------------------------------------------------------ il2cpp API */
typedef struct Il2CppDomain   Il2CppDomain;
typedef struct Il2CppAssembly Il2CppAssembly;
typedef struct Il2CppImage    Il2CppImage;
typedef struct Il2CppClass    Il2CppClass;
typedef struct Il2CppString   Il2CppString;
typedef struct MethodInfo     MethodInfo;

static Il2CppDomain*  (*f_domain_get)(void);
static void*          (*f_thread_attach)(Il2CppDomain*);
static const Il2CppAssembly** (*f_domain_get_assemblies)(const Il2CppDomain*, usize*);
static Il2CppImage*   (*f_assembly_get_image)(const Il2CppAssembly*);
static const char*    (*f_image_get_name)(Il2CppImage*);
static Il2CppClass*   (*f_class_from_name)(const Il2CppImage*, const char*, const char*);
static const MethodInfo* (*f_class_get_method_from_name)(Il2CppClass*, const char*, int);
static Il2CppString*  (*f_string_new)(const char*);
static u32            (*f_gchandle_new)(void*, int);

#define MPTR(mi) (*(void**)(mi))

/* ----------------------------------------------------------- game state */
__attribute__((visibility("hidden"))) void* ps3pad_tramp;
extern void ps3pad_stub(void);

static int g_ready, g_frames;

static void* m_l_press, *m_l_rel, *m_r_press, *m_r_rel;
static void* m_j_press, *m_j_rel, *m_c_press, *m_c_rel;
static void* m_d_press, *m_f_press, *m_t_press;

static float (*f_GetAxisRaw)(Il2CppString*);
static float (*f_GetAxis)(Il2CppString*);
static u8    (*f_GetKey)(int);
static u8    (*f_GetKeyDown)(int);
static u8    (*f_GetButton)(Il2CppString*);
static u8    (*f_GetButtonDown)(Il2CppString*);

static Il2CppString* s_h, *s_v, *s_f1, *s_f2, *s_f3, *s_jump, *s_submit, *s_cancel;

static int g_last_left, g_last_right, g_last_jump, g_last_attack;
static u32 g_last_mask;

/* KeyCode values (stable across Unity versions) */
#define KC_LEFT_ARROW   276
#define KC_RIGHT_ARROW  275
#define KC_JOY_BTN(n)   (350 + (n))   /* 1st joystick, buttons 0..19 */
#define KC_JOY1_BTN(n)  (370 + (n))   /* 2nd joystick, buttons 0..19 */

/* --------------------------------------------------------- configuration */
/* defaults chosen so that movement + the four action buttons work whichever
   button-numbering scheme the device's Android build reports */
static int g_deadzone  = 350;   /* 0.350 of full stick deflection */
static int g_b_jump    = 0;     /* Cross                     */
static int g_b_jump2   = -1;
static int g_b_attack  = 3;     /* Square (alt scheme: 2)    */
static int g_b_attack2 = 2;
static int g_b_dash    = 1;     /* Circle                    */
static int g_b_dash2   = -1;
static int g_b_special = 4;     /* Triangle                  */
static int g_b_special2= -1;
static int g_b_trans   = 6;     /* L1                        */
static int g_b_trans2  = 7;     /* R1                        */
static int g_dpad_move = 1;
static int g_log_state = 1;
static int g_log_btn   = 1;

static int str_eq(const char* a, const char* b)
{
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

static int cfg_int(const char* s, int def)
{
    int v = 0, neg = 0, any = 0;
    while (*s == ' ' || *s == '\t') s++;
    if (*s == '-') { neg = 1; s++; }
    while (*s >= '0' && *s <= '9') { v = v * 10 + (*s - '0'); s++; any = 1; }
    if (!any) return def;
    return neg ? -v : v;
}

static void cfg_apply(const char* k, const char* v)
{
    if      (str_eq(k, "deadzone"))   g_deadzone   = cfg_int(v, g_deadzone);
    else if (str_eq(k, "jump"))       g_b_jump     = cfg_int(v, g_b_jump);
    else if (str_eq(k, "jump2"))      g_b_jump2    = cfg_int(v, g_b_jump2);
    else if (str_eq(k, "attack"))     g_b_attack   = cfg_int(v, g_b_attack);
    else if (str_eq(k, "attack2"))    g_b_attack2  = cfg_int(v, g_b_attack2);
    else if (str_eq(k, "dash"))       g_b_dash     = cfg_int(v, g_b_dash);
    else if (str_eq(k, "dash2"))      g_b_dash2    = cfg_int(v, g_b_dash2);
    else if (str_eq(k, "special"))    g_b_special  = cfg_int(v, g_b_special);
    else if (str_eq(k, "special2"))   g_b_special2 = cfg_int(v, g_b_special2);
    else if (str_eq(k, "transform"))  g_b_trans    = cfg_int(v, g_b_trans);
    else if (str_eq(k, "transform2")) g_b_trans2   = cfg_int(v, g_b_trans2);
    else if (str_eq(k, "dpad_move"))  g_dpad_move  = cfg_int(v, g_dpad_move);
    else if (str_eq(k, "log_state"))  g_log_state  = cfg_int(v, g_log_state);
    else if (str_eq(k, "log_buttons"))g_log_btn    = cfg_int(v, g_log_btn);
}

static void cfg_load(void)
{
    static const char* paths[] = {
        "/sdcard/Android/data/com.blackpanther.ninjaarashi/files/ps3pad.ini",
        "/storage/emulated/0/Android/data/com.blackpanther.ninjaarashi/files/ps3pad.ini",
        "/sdcard/ps3pad.ini",
        0
    };
    char buf[1024];
    void* fp = 0;
    int i, n;
    for (i = 0; paths[i] && !fp; i++) fp = fopen(paths[i], "r");
    if (!fp) { log_str("config: no ps3pad.ini, using defaults"); return; }
    n = (int)fread(buf, 1, sizeof(buf) - 1, fp);
    fclose(fp);
    if (n <= 0) return;
    buf[n] = 0;
    i = 0;
    while (i < n) {
        int ls = i, le, k = 0;
        char key[64], val[32];
        while (i < n && buf[i] != '\n' && buf[i] != '\r') i++;
        le = i;
        while (i < n && (buf[i] == '\n' || buf[i] == '\r')) i++;
        if (le <= ls || buf[ls] == '#' || buf[ls] == ';') continue;
        while (ls < le && buf[ls] != '=' && k < 60) {
            char c = buf[ls++];
            if (c != ' ' && c != '\t') key[k++] = c;
        }
        key[k] = 0;
        while (ls < le && (buf[ls] == '=' || buf[ls] == ' ' || buf[ls] == '\t')) ls++;
        k = 0;
        while (ls < le && k < 30) {
            char c = buf[ls++];
            if (c != ' ' && c != '\t') val[k++] = c;
        }
        val[k] = 0;
        if (k) cfg_apply(key, val);
    }
    log_str("config: loaded ps3pad.ini");
}

/* --------------------------------------------------------- code patching */
static int insn_unsafe(u32 i)
{
    if ((i & 0x7C000000) == 0x14000000) return 1;  /* b / bl                    */
    if ((i & 0xFF000000) == 0x54000000) return 1;  /* b.cond                    */
    if ((i & 0x7E000000) == 0x34000000) return 1;  /* cbz / cbnz                */
    if ((i & 0x7E000000) == 0x36000000) return 1;  /* tbz / tbnz                */
    if ((i & 0x9F000000) == 0x10000000) return 1;  /* adr                       */
    if ((i & 0x9F000000) == 0x90000000) return 1;  /* adrp                      */
    if ((i & 0x3B000000) == 0x18000000) return 1;  /* ldr <literal>             */
    if ((i & 0xFFFFFC1F) == 0xD61F0000) return 1;  /* br                        */
    if ((i & 0xFFFFFC1F) == 0xD63F0000) return 1;  /* blr                       */
    if (i == 0xD65F03C0) return 1;                 /* ret                       */
    return 0;
}

/*
 * Trampoline storage.  Android 10+ refuses PROT_EXEC on anonymous memory for
 * apps targeting API 29+, but allows it on private mappings of a read-only
 * file, so the trampolines live in this library's own (file backed) .bss.
 */
static u8 g_tramp_pool[16384] __attribute__((aligned(4096)));
static int g_tramp_used;
static int g_tramp_exec_ok;

static void* tramp_alloc(int bytes)
{
    u8* base;
    if (!g_tramp_exec_ok) {
        usize page = (usize)g_tramp_pool & ~(usize)0xFFF;
        if (mprotect((void*)page, (usize)0x4000, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            log_str("tramp: mprotect(.bss) denied");
            return 0;
        }
        g_tramp_exec_ok = 1;
    }
    if (g_tramp_used + bytes > (int)sizeof(g_tramp_pool)) {
        log_str("tramp: pool exhausted");
        return 0;
    }
    base = g_tramp_pool + g_tramp_used;
    g_tramp_used += (bytes + 15) & ~15;
    return base;
}

/* copy `insns` instructions from target, then jump to target + insns*4 */
static void* make_trampoline(void* target, int insns)
{
    void* p = tramp_alloc(insns * 4 + 16);
    u32* w;
    u64 back;
    if (!p) return 0;
    memcpy(p, target, (usize)(insns * 4));
    w = (u32*)((u8*)p + insns * 4);
    w[0] = 0x58000050;                   /* ldr x16, [pc, #8] */
    w[1] = 0xD61F0200;                   /* br  x16           */
    back = (u64)((u8*)target + insns * 4);
    memcpy(&w[2], &back, 8);
    cache_flush(p, (void*)((u8*)p + insns * 4 + 16));
    return p;
}

static int install_inline_hook(void* target, void* stub)
{
    u32 save[4], patch[4];
    u64 addr = (u64)stub;
    usize page = (usize)target & ~(usize)0xFFF;
    volatile u32* v = (volatile u32*)target;
    int off, found = -1, i, rejected = 0;

    /* find a window of four instructions that can be relocated verbatim */
    for (off = 0; off < 48; off++) {
        int ok = 1;
        for (i = 0; i < 4; i++) {
            if (insn_unsafe(v[off + i])) { ok = 0; break; }
        }
        if (ok) { found = off; break; }
        rejected++;
    }
    if (found < 0) {
        log_str("hook: no relocatable prologue window");
        return 0;
    }
    if (found)
        log_i("hook: skipping prologue words, offset=", found);

    for (i = 0; i < 4; i++) save[i] = v[found + i];
    ps3pad_tramp = make_trampoline((void*)((u8*)target + found * 4), 4);
    if (!ps3pad_tramp) return 0;

    if (mprotect((void*)page, 4096, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        log_str("hook: mprotect(.text) denied");
        return 0;
    }
    patch[0] = 0x58000050;                    /* ldr x16, [pc, #8] */
    patch[1] = 0xD61F0200;                    /* br  x16           */
    memcpy(&patch[2], &addr, 8);

    for (i = 0; i < 4; i++) v[found + i] = patch[i];
    cache_flush((void*)((u8*)target + found * 4), (void*)((u8*)target + found * 4 + 16));

    for (i = 0; i < 4; i++) {
        if (v[found + i] != patch[i]) {
            log_str("hook: patch did not stick, restoring");
            for (i = 0; i < 4; i++) v[found + i] = save[i];
            cache_flush((void*)((u8*)target + found * 4), (void*)((u8*)target + found * 4 + 16));
            return 0;
        }
    }
    log_hex("hook: patched Update at ", (unsigned long long)(usize)target + found * 4);
    log_hex("hook: trampoline at ", (unsigned long long)(usize)ps3pad_tramp);
    return 1;
}

/* ------------------------------------------------------- target validation */
/* A Dl_info for dladdr(): bionic fills in file/base/symbol information, which
   lets us refuse to patch anything that is not really code in libil2cpp.so.
   (If the assumed MethodInfo layout were wrong we would otherwise write the
    trampoline jump over random memory.) */
typedef struct { const char* fname; void* fbase; const char* sname; void* saddr; } DlInfo;

static int is_il2cpp_code(void* p)
{
    DlInfo info;
    if (!p || ((usize)p & 3)) return 0;
    if (!f_dladdr) return 1;                 /* cannot validate, proceed */
    if (f_dladdr(p, &info) == 0 || !info.fname) return 0;
    if (!strstr(info.fname, "libil2cpp")) {
        log_two("refusing to patch code in ", info.fname);
        return 0;
    }
    return 1;
}

/* ------------------------------------------------------------- il2cpp bits */
static Il2CppImage* find_image(const char* want)
{
    usize n = 0, i;
    const Il2CppAssembly** asms = f_domain_get_assemblies(f_domain_get(), &n);
    if (!asms || n == 0 || n > 2000) return 0;
    for (i = 0; i < n; i++) {
        Il2CppImage* img = f_assembly_get_image(asms[i]);
        const char* nm;
        if (!img) continue;
        nm = f_image_get_name(img);
        if (nm && str_eq(nm, want)) return img;
    }
    return 0;
}

static const MethodInfo* find_method(Il2CppImage* img, const char* cls, const char* name)
{
    static const char* namespaces[] = { "", "System", "UnityEngine", "NinjaArashi", "Assembly-CSharp" };
    int i;
    for (i = 0; i < 5; i++) {
        Il2CppClass* k = f_class_from_name(img, namespaces[i], cls);
        const MethodInfo* mi;
        if (!k) continue;
        mi = f_class_get_method_from_name(k, name, 0);
        if (mi && MPTR(mi)) return mi;
    }
    return 0;
}

static void* method_ptr(Il2CppImage* img, const char* cls, const char* name)
{
    const MethodInfo* mi = find_method(img, cls, name);
    void* p;
    if (!mi) { log_two("missing: ", name); return 0; }
    p = MPTR(mi);
    if (!is_il2cpp_code(p)) { log_two("bad code pointer: ", name); return 0; }
    return p;
}

static Il2CppString* pin(const char* s)
{
    Il2CppString* o = f_string_new(s);
    if (o && f_gchandle_new) f_gchandle_new(o, 1);   /* strong handle: never freed */
    return o;
}

static void resolve_input_api(Il2CppImage* core)
{
    Il2CppClass* k = f_class_from_name(core, "UnityEngine", "Input");
    const MethodInfo* mi;
    if (!k) { log_str("UnityEngine.Input class not found"); return; }
    mi = f_class_get_method_from_name(k, "GetAxisRaw", 1);
    if (mi) f_GetAxisRaw = (float (*)(Il2CppString*))MPTR(mi);
    mi = f_class_get_method_from_name(k, "GetAxis", 1);
    if (mi) f_GetAxis = (float (*)(Il2CppString*))MPTR(mi);
    mi = f_class_get_method_from_name(k, "GetKey", 1);
    if (mi) f_GetKey = (u8 (*)(int))MPTR(mi);
    mi = f_class_get_method_from_name(k, "GetKeyDown", 1);
    if (mi) f_GetKeyDown = (u8 (*)(int))MPTR(mi);
    mi = f_class_get_method_from_name(k, "GetButton", 1);
    if (mi) f_GetButton = (u8 (*)(Il2CppString*))MPTR(mi);
    mi = f_class_get_method_from_name(k, "GetButtonDown", 1);
    if (mi) f_GetButtonDown = (u8 (*)(Il2CppString*))MPTR(mi);

    s_h      = pin("Horizontal");
    s_v      = pin("Vertical");
    s_f1     = pin("Fire1");
    s_f2     = pin("Fire2");
    s_f3     = pin("Fire3");
    s_jump   = pin("Jump");
    s_submit = pin("Submit");
    s_cancel = pin("Cancel");

    log_i("input: GetAxisRaw=", f_GetAxisRaw ? 1 : 0);
    log_i("input: GetKey=", f_GetKey ? 1 : 0);
    log_i("input: GetKeyDown=", f_GetKeyDown ? 1 : 0);
    log_i("input: GetButton=", f_GetButton ? 1 : 0);
    log_i("input: GetButtonDown=", f_GetButtonDown ? 1 : 0);
}

/* ------------------------------------------------------------ pad reading */
/* current pad state, one bit per joystick button index */
static u32 read_state(void)
{
    u32 m = 0;
    int i;
    if (f_GetKey) {
        for (i = 0; i < 20; i++)
            if (f_GetKey(KC_JOY_BTN(i)) || f_GetKey(KC_JOY1_BTN(i))) m |= 1u << i;
    }
    if (f_GetButton) {
        if (f_GetButton(s_f1))     m |= 1u << 0;
        if (f_GetButton(s_f2))     m |= 1u << 1;
        if (f_GetButton(s_f3))     m |= 1u << 2;
        if (f_GetButton(s_jump))   m |= 1u << 3;
        if (f_GetButton(s_submit)) m |= 1u << 0;
        if (f_GetButton(s_cancel)) m |= 1u << 1;
    }
    return m;
}

/* buttons that went down since the previous frame (catches sub-frame taps) */
static u32 read_down(u32 held)
{
    int idx[10];
    u32 m = 0;
    int i;
    idx[0] = g_b_jump;    idx[1] = g_b_jump2;
    idx[2] = g_b_attack;  idx[3] = g_b_attack2;
    idx[4] = g_b_dash;    idx[5] = g_b_dash2;
    idx[6] = g_b_special; idx[7] = g_b_special2;
    idx[8] = g_b_trans;   idx[9] = g_b_trans2;
    if (f_GetKeyDown) {
        for (i = 0; i < 10; i++) {
            int b = idx[i];
            if (b < 0 || b > 19 || (held & (1u << b))) continue;
            if (f_GetKeyDown(KC_JOY_BTN(b)) || f_GetKeyDown(KC_JOY1_BTN(b))) m |= 1u << b;
        }
    }
    if (f_GetButtonDown) {
        if (f_GetButtonDown(s_f1))     m |= 1u << 0;
        if (f_GetButtonDown(s_f2))     m |= 1u << 1;
        if (f_GetButtonDown(s_f3))     m |= 1u << 2;
        if (f_GetButtonDown(s_jump))   m |= 1u << 3;
        if (f_GetButtonDown(s_submit)) m |= 1u << 0;
        if (f_GetButtonDown(s_cancel)) m |= 1u << 1;
    }
    return m;
}

static float axis_of(Il2CppString* s)
{
    if (!s) return 0.0f;
    if (f_GetAxisRaw) return f_GetAxisRaw(s);
    if (f_GetAxis)    return f_GetAxis(s);
    return 0.0f;
}

static int btn(u32 mask, int idx)
{
    if (idx < 0 || idx > 19) return 0;
    return (int)((mask >> idx) & 1u);
}

static int any2(u32 mask, int a, int b)
{
    return (a >= 0 && btn(mask, a)) || (b >= 0 && btn(mask, b));
}

static void call0(void* fn, void* self)
{
    ((void (*)(void*))fn)(self);
}

static void log_button_edges(u32 held)
{
    u32 now = held & ~g_last_mask;
    int i, n = 0;
    char line[100];
    for (i = 0; i < 20; i++) {
        if (now & (1u << i)) {
            line[n++] = 'b'; line[n++] = 't'; line[n++] = 'n'; line[n++] = ' ';
            if (i >= 10) line[n++] = (char)('0' + i / 10);
            line[n++] = (char)('0' + i % 10);
            line[n++] = ' ';
        }
    }
    if (n) { line[n] = 0; log_two("pad: press ", line); }
}

static void apply(void* self)
{
    u32 held = read_state();
    u32 pressed = held | read_down(held);
    float ax, dz, hz;
    int left, right, jump, attack, dash, special, trans;

    if (held != g_last_mask) {
        if (g_log_btn) log_button_edges(held);
        g_last_mask = held;
    }

    ax = axis_of(s_h);
    dz = (float)g_deadzone / 1000.0f;
    hz = dz * 0.6f;

    left = g_last_left;
    right = g_last_right;
    if (left)  { if (ax > -hz) left = 0; }
    else if (right) { if (ax < hz) right = 0; }
    if (!left && !right) {
        if (ax < -dz) left = 1;
        else if (ax > dz) right = 1;
    }
    if (g_dpad_move && f_GetKey) {
        if (f_GetKey(KC_LEFT_ARROW))  { left = 1; right = 0; }
        else if (f_GetKey(KC_RIGHT_ARROW)) { right = 1; left = 0; }
    }
    if (left && right) { left = 0; right = 0; }

    if (left != g_last_left) {
        if (m_l_press && m_l_rel) call0(left ? m_l_press : m_l_rel, self);
        g_last_left = left;
    }
    if (right != g_last_right) {
        if (m_r_press && m_r_rel) call0(right ? m_r_press : m_r_rel, self);
        g_last_right = right;
    }

    jump    = any2(pressed, g_b_jump, g_b_jump2);
    attack  = any2(pressed, g_b_attack, g_b_attack2);
    dash    = any2(pressed, g_b_dash, g_b_dash2);
    special = any2(pressed, g_b_special, g_b_special2);
    trans   = any2(pressed, g_b_trans, g_b_trans2);

    if (jump != g_last_jump) {
        if (m_j_press && m_j_rel) call0(jump ? m_j_press : m_j_rel, self);
        g_last_jump = jump;
    }
    if (attack != g_last_attack) {
        if (m_c_press && m_c_rel) call0(attack ? m_c_press : m_c_rel, self);
        g_last_attack = attack;
    }
    if (dash && m_d_press)    call0(m_d_press, self);
    if (special && m_f_press) call0(m_f_press, self);
    if (trans && m_t_press)   call0(m_t_press, self);

    if (g_log_state && g_frames > 0 && (g_frames % 900) == 0) {
        log_i("status: frames=", g_frames);
        log_f("  axis X = ", ax);
        log_hex("  buttons = ", (unsigned long long)pressed);
    }
}

/* called from ps3pad_stub with x0 = NinjaMovementScript instance */
__attribute__((visibility("hidden"))) void ps3pad_on_update(void* self)
{
    if (!self || !g_ready) return;
    g_frames++;
    apply(self);
}

/* ------------------------------------------------------------ bootstrap */
static void* watcher(void* unused)
{
    void* h = 0;
    Il2CppImage* game = 0;
    Il2CppImage* core = 0;
    const MethodInfo* mi_upd = 0;
    int i, tries;

    (void)unused;
    log_str("watcher: started, waiting for libil2cpp.so");
    log_i("watcher: dladdr available=", f_dladdr ? 1 : 0);
    for (i = 0; i < 3000 && !h; i++) {
        h = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
        if (!h) usleep(20000);
    }
    if (!h) { log_str("watcher: libil2cpp.so never appeared"); for (;;) usleep(1000000); }

    f_domain_get = (Il2CppDomain* (*)(void))dlsym(h, "il2cpp_domain_get");
    f_thread_attach = (void* (*)(Il2CppDomain*))dlsym(h, "il2cpp_thread_attach");
    f_domain_get_assemblies = (const Il2CppAssembly** (*)(const Il2CppDomain*, usize*))dlsym(h, "il2cpp_domain_get_assemblies");
    f_assembly_get_image = (Il2CppImage* (*)(const Il2CppAssembly*))dlsym(h, "il2cpp_assembly_get_image");
    f_image_get_name = (const char* (*)(Il2CppImage*))dlsym(h, "il2cpp_image_get_name");
    f_class_from_name = (Il2CppClass* (*)(const Il2CppImage*, const char*, const char*))dlsym(h, "il2cpp_class_from_name");
    f_class_get_method_from_name = (const MethodInfo* (*)(Il2CppClass*, const char*, int))dlsym(h, "il2cpp_class_get_method_from_name");
    f_string_new = (Il2CppString* (*)(const char*))dlsym(h, "il2cpp_string_new");
    f_gchandle_new = (u32 (*)(void*, int))dlsym(h, "il2cpp_gchandle_new");

    if (!f_domain_get || !f_thread_attach || !f_domain_get_assemblies ||
        !f_assembly_get_image || !f_image_get_name || !f_class_from_name ||
        !f_class_get_method_from_name || !f_string_new) {
        log_str("watcher: required il2cpp symbols missing");
        for (;;) usleep(1000000);
    }
    log_str("watcher: il2cpp C API bound");

    /* wait until the runtime is up and Assembly-CSharp.dll is loaded */
    for (tries = 0; tries < 1200 && !game; tries++) {
        Il2CppDomain* d = f_domain_get();
        if (d) {
            f_thread_attach(d);
            game = find_image("Assembly-CSharp.dll");
        }
        if (!game) usleep(150000);
    }
    if (!game) { log_str("watcher: Assembly-CSharp.dll never loaded"); for (;;) usleep(1000000); }
    log_str("watcher: Assembly-CSharp.dll loaded");

    core = find_image("UnityEngine.CoreModule.dll");
    if (core) resolve_input_api(core);
    else log_str("watcher: UnityEngine.CoreModule.dll not found");

    for (tries = 0; tries < 600 && !mi_upd; tries++) {
        mi_upd = find_method(game, "NinjaMovementScript", "Update");
        if (!mi_upd) usleep(150000);
    }
    if (!mi_upd) { log_str("watcher: NinjaMovementScript.Update not found"); for (;;) usleep(1000000); }
    log_hex("watcher: Update MethodInfo ", (unsigned long long)(usize)mi_upd);
    log_hex("watcher: Update code at ", (unsigned long long)(usize)MPTR(mi_upd));

    m_l_press = method_ptr(game, "NinjaMovementScript", "Button_Left_press");
    m_l_rel   = method_ptr(game, "NinjaMovementScript", "Button_Left_release");
    m_r_press = method_ptr(game, "NinjaMovementScript", "Button_Right_press");
    m_r_rel   = method_ptr(game, "NinjaMovementScript", "Button_Right_release");
    m_j_press = method_ptr(game, "NinjaMovementScript", "Button_Jump_press");
    m_j_rel   = method_ptr(game, "NinjaMovementScript", "Button_Jump_release");
    m_c_press = method_ptr(game, "NinjaMovementScript", "Button_Check_press");
    m_c_rel   = method_ptr(game, "NinjaMovementScript", "Button_Check_release");
    m_d_press = method_ptr(game, "NinjaMovementScript", "Button_Dash_press");
    m_f_press = method_ptr(game, "NinjaMovementScript", "Button_Fi_press");
    m_t_press = method_ptr(game, "NinjaMovementScript", "Button_Trans_press");
    log_i("game: move=", (m_l_press && m_r_press) ? 1 : 0);
    log_i("game: jump=", (m_j_press && m_j_rel) ? 1 : 0);
    log_i("game: attack=", (m_c_press && m_c_rel) ? 1 : 0);
    log_i("game: dash=", m_d_press ? 1 : 0);
    log_i("game: special=", m_f_press ? 1 : 0);
    log_i("game: transform=", m_t_press ? 1 : 0);

    cfg_load();
    log_i("cfg: deadzone=", g_deadzone);
    log_i("cfg: jump=", g_b_jump);
    log_i("cfg: attack=", g_b_attack);
    log_i("cfg: dash=", g_b_dash);
    log_i("cfg: special=", g_b_special);
    log_i("cfg: transform=", g_b_trans);
    log_i("cfg: dpad_move=", g_dpad_move);

    if (!m_l_press || !m_r_press || !m_j_press || !m_c_press) {
        log_str("watcher: control API incomplete, not hooking");
        for (;;) usleep(1000000);
    }

    if (!is_il2cpp_code(MPTR(mi_upd))) {
        log_str("watcher: Update pointer does not look like libil2cpp code");
        for (;;) usleep(1000000);
    }

    g_ready = 1;
    if (install_inline_hook(MPTR(mi_upd), (void*)&ps3pad_stub))
        log_str("ps3pad ACTIVE - DualShock 3 ready (hook installed)");
    else {
        g_ready = 0;
        log_str("ps3pad hook failed - game left untouched");
    }

    for (;;) usleep(1000000);   /* keep the il2cpp-attached thread alive */
}

__attribute__((constructor)) static void ps3pad_init(void)
{
    unsigned long t;
    logcat_open();
    dladdr_open();
    log_str("ps3pad: library loaded (constructor)");
    if (pthread_create(&t, 0, watcher, 0) != 0)
        log_str("ps3pad: could not start watcher thread");
}

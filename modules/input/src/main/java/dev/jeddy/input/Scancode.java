package dev.jeddy.input;

/**
 * Physical key positions ({@code SDL_Scancode} values), generated from {@code SDL_scancode.h}.
 * Bindings use scancodes, so WASD stays in place on AZERTY or Dvorak layouts; {@link
 * KeyLabels#label(int)} gives the layout's label for display.
 *
 * <p>{@link #name(int)} and {@link #fromName(String)} give stable names for settings files (the SDL
 * constant without its {@code SDL_SCANCODE_} prefix, for example {@code LSHIFT}).
 *
 * <p>Thread ownership: constants; the lookups are thread-safe.
 */
public final class Scancode {
    private Scancode() {}

    /** Number of scancodes ({@code SDL_SCANCODE_COUNT}); every value is below it. */
    public static final int COUNT = 512;

    /** Not a key. */
    public static final int UNKNOWN = 0;

    /** {@code SDL_SCANCODE_A}. */
    public static final int A = 4;

    /** {@code SDL_SCANCODE_B}. */
    public static final int B = 5;

    /** {@code SDL_SCANCODE_C}. */
    public static final int C = 6;

    /** {@code SDL_SCANCODE_D}. */
    public static final int D = 7;

    /** {@code SDL_SCANCODE_E}. */
    public static final int E = 8;

    /** {@code SDL_SCANCODE_F}. */
    public static final int F = 9;

    /** {@code SDL_SCANCODE_G}. */
    public static final int G = 10;

    /** {@code SDL_SCANCODE_H}. */
    public static final int H = 11;

    /** {@code SDL_SCANCODE_I}. */
    public static final int I = 12;

    /** {@code SDL_SCANCODE_J}. */
    public static final int J = 13;

    /** {@code SDL_SCANCODE_K}. */
    public static final int K = 14;

    /** {@code SDL_SCANCODE_L}. */
    public static final int L = 15;

    /** {@code SDL_SCANCODE_M}. */
    public static final int M = 16;

    /** {@code SDL_SCANCODE_N}. */
    public static final int N = 17;

    /** {@code SDL_SCANCODE_O}. */
    public static final int O = 18;

    /** {@code SDL_SCANCODE_P}. */
    public static final int P = 19;

    /** {@code SDL_SCANCODE_Q}. */
    public static final int Q = 20;

    /** {@code SDL_SCANCODE_R}. */
    public static final int R = 21;

    /** {@code SDL_SCANCODE_S}. */
    public static final int S = 22;

    /** {@code SDL_SCANCODE_T}. */
    public static final int T = 23;

    /** {@code SDL_SCANCODE_U}. */
    public static final int U = 24;

    /** {@code SDL_SCANCODE_V}. */
    public static final int V = 25;

    /** {@code SDL_SCANCODE_W}. */
    public static final int W = 26;

    /** {@code SDL_SCANCODE_X}. */
    public static final int X = 27;

    /** {@code SDL_SCANCODE_Y}. */
    public static final int Y = 28;

    /** {@code SDL_SCANCODE_Z}. */
    public static final int Z = 29;

    /** {@code SDL_SCANCODE_1}. */
    public static final int DIGIT_1 = 30;

    /** {@code SDL_SCANCODE_2}. */
    public static final int DIGIT_2 = 31;

    /** {@code SDL_SCANCODE_3}. */
    public static final int DIGIT_3 = 32;

    /** {@code SDL_SCANCODE_4}. */
    public static final int DIGIT_4 = 33;

    /** {@code SDL_SCANCODE_5}. */
    public static final int DIGIT_5 = 34;

    /** {@code SDL_SCANCODE_6}. */
    public static final int DIGIT_6 = 35;

    /** {@code SDL_SCANCODE_7}. */
    public static final int DIGIT_7 = 36;

    /** {@code SDL_SCANCODE_8}. */
    public static final int DIGIT_8 = 37;

    /** {@code SDL_SCANCODE_9}. */
    public static final int DIGIT_9 = 38;

    /** {@code SDL_SCANCODE_0}. */
    public static final int DIGIT_0 = 39;

    /** {@code SDL_SCANCODE_RETURN}. */
    public static final int RETURN = 40;

    /** {@code SDL_SCANCODE_ESCAPE}. */
    public static final int ESCAPE = 41;

    /** {@code SDL_SCANCODE_BACKSPACE}. */
    public static final int BACKSPACE = 42;

    /** {@code SDL_SCANCODE_TAB}. */
    public static final int TAB = 43;

    /** {@code SDL_SCANCODE_SPACE}. */
    public static final int SPACE = 44;

    /** {@code SDL_SCANCODE_MINUS}. */
    public static final int MINUS = 45;

    /** {@code SDL_SCANCODE_EQUALS}. */
    public static final int EQUALS = 46;

    /** {@code SDL_SCANCODE_LEFTBRACKET}. */
    public static final int LEFTBRACKET = 47;

    /** {@code SDL_SCANCODE_RIGHTBRACKET}. */
    public static final int RIGHTBRACKET = 48;

    /** {@code SDL_SCANCODE_BACKSLASH}. */
    public static final int BACKSLASH = 49;

    /** {@code SDL_SCANCODE_NONUSHASH}. */
    public static final int NONUSHASH = 50;

    /** {@code SDL_SCANCODE_SEMICOLON}. */
    public static final int SEMICOLON = 51;

    /** {@code SDL_SCANCODE_APOSTROPHE}. */
    public static final int APOSTROPHE = 52;

    /** {@code SDL_SCANCODE_GRAVE}. */
    public static final int GRAVE = 53;

    /** {@code SDL_SCANCODE_COMMA}. */
    public static final int COMMA = 54;

    /** {@code SDL_SCANCODE_PERIOD}. */
    public static final int PERIOD = 55;

    /** {@code SDL_SCANCODE_SLASH}. */
    public static final int SLASH = 56;

    /** {@code SDL_SCANCODE_CAPSLOCK}. */
    public static final int CAPSLOCK = 57;

    /** {@code SDL_SCANCODE_F1}. */
    public static final int F1 = 58;

    /** {@code SDL_SCANCODE_F2}. */
    public static final int F2 = 59;

    /** {@code SDL_SCANCODE_F3}. */
    public static final int F3 = 60;

    /** {@code SDL_SCANCODE_F4}. */
    public static final int F4 = 61;

    /** {@code SDL_SCANCODE_F5}. */
    public static final int F5 = 62;

    /** {@code SDL_SCANCODE_F6}. */
    public static final int F6 = 63;

    /** {@code SDL_SCANCODE_F7}. */
    public static final int F7 = 64;

    /** {@code SDL_SCANCODE_F8}. */
    public static final int F8 = 65;

    /** {@code SDL_SCANCODE_F9}. */
    public static final int F9 = 66;

    /** {@code SDL_SCANCODE_F10}. */
    public static final int F10 = 67;

    /** {@code SDL_SCANCODE_F11}. */
    public static final int F11 = 68;

    /** {@code SDL_SCANCODE_F12}. */
    public static final int F12 = 69;

    /** {@code SDL_SCANCODE_PRINTSCREEN}. */
    public static final int PRINTSCREEN = 70;

    /** {@code SDL_SCANCODE_SCROLLLOCK}. */
    public static final int SCROLLLOCK = 71;

    /** {@code SDL_SCANCODE_PAUSE}. */
    public static final int PAUSE = 72;

    /** {@code SDL_SCANCODE_INSERT}. */
    public static final int INSERT = 73;

    /** {@code SDL_SCANCODE_HOME}. */
    public static final int HOME = 74;

    /** {@code SDL_SCANCODE_PAGEUP}. */
    public static final int PAGEUP = 75;

    /** {@code SDL_SCANCODE_DELETE}. */
    public static final int DELETE = 76;

    /** {@code SDL_SCANCODE_END}. */
    public static final int END = 77;

    /** {@code SDL_SCANCODE_PAGEDOWN}. */
    public static final int PAGEDOWN = 78;

    /** {@code SDL_SCANCODE_RIGHT}. */
    public static final int RIGHT = 79;

    /** {@code SDL_SCANCODE_LEFT}. */
    public static final int LEFT = 80;

    /** {@code SDL_SCANCODE_DOWN}. */
    public static final int DOWN = 81;

    /** {@code SDL_SCANCODE_UP}. */
    public static final int UP = 82;

    /** {@code SDL_SCANCODE_NUMLOCKCLEAR}. */
    public static final int NUMLOCKCLEAR = 83;

    /** {@code SDL_SCANCODE_KP_DIVIDE}. */
    public static final int KP_DIVIDE = 84;

    /** {@code SDL_SCANCODE_KP_MULTIPLY}. */
    public static final int KP_MULTIPLY = 85;

    /** {@code SDL_SCANCODE_KP_MINUS}. */
    public static final int KP_MINUS = 86;

    /** {@code SDL_SCANCODE_KP_PLUS}. */
    public static final int KP_PLUS = 87;

    /** {@code SDL_SCANCODE_KP_ENTER}. */
    public static final int KP_ENTER = 88;

    /** {@code SDL_SCANCODE_KP_1}. */
    public static final int KP_1 = 89;

    /** {@code SDL_SCANCODE_KP_2}. */
    public static final int KP_2 = 90;

    /** {@code SDL_SCANCODE_KP_3}. */
    public static final int KP_3 = 91;

    /** {@code SDL_SCANCODE_KP_4}. */
    public static final int KP_4 = 92;

    /** {@code SDL_SCANCODE_KP_5}. */
    public static final int KP_5 = 93;

    /** {@code SDL_SCANCODE_KP_6}. */
    public static final int KP_6 = 94;

    /** {@code SDL_SCANCODE_KP_7}. */
    public static final int KP_7 = 95;

    /** {@code SDL_SCANCODE_KP_8}. */
    public static final int KP_8 = 96;

    /** {@code SDL_SCANCODE_KP_9}. */
    public static final int KP_9 = 97;

    /** {@code SDL_SCANCODE_KP_0}. */
    public static final int KP_0 = 98;

    /** {@code SDL_SCANCODE_KP_PERIOD}. */
    public static final int KP_PERIOD = 99;

    /** {@code SDL_SCANCODE_NONUSBACKSLASH}. */
    public static final int NONUSBACKSLASH = 100;

    /** {@code SDL_SCANCODE_APPLICATION}. */
    public static final int APPLICATION = 101;

    /** {@code SDL_SCANCODE_POWER}. */
    public static final int POWER = 102;

    /** {@code SDL_SCANCODE_KP_EQUALS}. */
    public static final int KP_EQUALS = 103;

    /** {@code SDL_SCANCODE_F13}. */
    public static final int F13 = 104;

    /** {@code SDL_SCANCODE_F14}. */
    public static final int F14 = 105;

    /** {@code SDL_SCANCODE_F15}. */
    public static final int F15 = 106;

    /** {@code SDL_SCANCODE_F16}. */
    public static final int F16 = 107;

    /** {@code SDL_SCANCODE_F17}. */
    public static final int F17 = 108;

    /** {@code SDL_SCANCODE_F18}. */
    public static final int F18 = 109;

    /** {@code SDL_SCANCODE_F19}. */
    public static final int F19 = 110;

    /** {@code SDL_SCANCODE_F20}. */
    public static final int F20 = 111;

    /** {@code SDL_SCANCODE_F21}. */
    public static final int F21 = 112;

    /** {@code SDL_SCANCODE_F22}. */
    public static final int F22 = 113;

    /** {@code SDL_SCANCODE_F23}. */
    public static final int F23 = 114;

    /** {@code SDL_SCANCODE_F24}. */
    public static final int F24 = 115;

    /** {@code SDL_SCANCODE_EXECUTE}. */
    public static final int EXECUTE = 116;

    /** {@code SDL_SCANCODE_HELP}. */
    public static final int HELP = 117;

    /** {@code SDL_SCANCODE_MENU}. */
    public static final int MENU = 118;

    /** {@code SDL_SCANCODE_SELECT}. */
    public static final int SELECT = 119;

    /** {@code SDL_SCANCODE_STOP}. */
    public static final int STOP = 120;

    /** {@code SDL_SCANCODE_AGAIN}. */
    public static final int AGAIN = 121;

    /** {@code SDL_SCANCODE_UNDO}. */
    public static final int UNDO = 122;

    /** {@code SDL_SCANCODE_CUT}. */
    public static final int CUT = 123;

    /** {@code SDL_SCANCODE_COPY}. */
    public static final int COPY = 124;

    /** {@code SDL_SCANCODE_PASTE}. */
    public static final int PASTE = 125;

    /** {@code SDL_SCANCODE_FIND}. */
    public static final int FIND = 126;

    /** {@code SDL_SCANCODE_MUTE}. */
    public static final int MUTE = 127;

    /** {@code SDL_SCANCODE_VOLUMEUP}. */
    public static final int VOLUMEUP = 128;

    /** {@code SDL_SCANCODE_VOLUMEDOWN}. */
    public static final int VOLUMEDOWN = 129;

    /** {@code SDL_SCANCODE_LOCKINGCAPSLOCK}. */
    public static final int LOCKINGCAPSLOCK = 130;

    /** {@code SDL_SCANCODE_LOCKINGNUMLOCK}. */
    public static final int LOCKINGNUMLOCK = 131;

    /** {@code SDL_SCANCODE_LOCKINGSCROLLLOCK}. */
    public static final int LOCKINGSCROLLLOCK = 132;

    /** {@code SDL_SCANCODE_KP_COMMA}. */
    public static final int KP_COMMA = 133;

    /** {@code SDL_SCANCODE_KP_EQUALSAS400}. */
    public static final int KP_EQUALSAS400 = 134;

    /** {@code SDL_SCANCODE_INTERNATIONAL1}. */
    public static final int INTERNATIONAL1 = 135;

    /** {@code SDL_SCANCODE_INTERNATIONAL2}. */
    public static final int INTERNATIONAL2 = 136;

    /** {@code SDL_SCANCODE_INTERNATIONAL3}. */
    public static final int INTERNATIONAL3 = 137;

    /** {@code SDL_SCANCODE_INTERNATIONAL4}. */
    public static final int INTERNATIONAL4 = 138;

    /** {@code SDL_SCANCODE_INTERNATIONAL5}. */
    public static final int INTERNATIONAL5 = 139;

    /** {@code SDL_SCANCODE_INTERNATIONAL6}. */
    public static final int INTERNATIONAL6 = 140;

    /** {@code SDL_SCANCODE_INTERNATIONAL7}. */
    public static final int INTERNATIONAL7 = 141;

    /** {@code SDL_SCANCODE_INTERNATIONAL8}. */
    public static final int INTERNATIONAL8 = 142;

    /** {@code SDL_SCANCODE_INTERNATIONAL9}. */
    public static final int INTERNATIONAL9 = 143;

    /** {@code SDL_SCANCODE_LANG1}. */
    public static final int LANG1 = 144;

    /** {@code SDL_SCANCODE_LANG2}. */
    public static final int LANG2 = 145;

    /** {@code SDL_SCANCODE_LANG3}. */
    public static final int LANG3 = 146;

    /** {@code SDL_SCANCODE_LANG4}. */
    public static final int LANG4 = 147;

    /** {@code SDL_SCANCODE_LANG5}. */
    public static final int LANG5 = 148;

    /** {@code SDL_SCANCODE_LANG6}. */
    public static final int LANG6 = 149;

    /** {@code SDL_SCANCODE_LANG7}. */
    public static final int LANG7 = 150;

    /** {@code SDL_SCANCODE_LANG8}. */
    public static final int LANG8 = 151;

    /** {@code SDL_SCANCODE_LANG9}. */
    public static final int LANG9 = 152;

    /** {@code SDL_SCANCODE_ALTERASE}. */
    public static final int ALTERASE = 153;

    /** {@code SDL_SCANCODE_SYSREQ}. */
    public static final int SYSREQ = 154;

    /** {@code SDL_SCANCODE_CANCEL}. */
    public static final int CANCEL = 155;

    /** {@code SDL_SCANCODE_CLEAR}. */
    public static final int CLEAR = 156;

    /** {@code SDL_SCANCODE_PRIOR}. */
    public static final int PRIOR = 157;

    /** {@code SDL_SCANCODE_RETURN2}. */
    public static final int RETURN2 = 158;

    /** {@code SDL_SCANCODE_SEPARATOR}. */
    public static final int SEPARATOR = 159;

    /** {@code SDL_SCANCODE_OUT}. */
    public static final int OUT = 160;

    /** {@code SDL_SCANCODE_OPER}. */
    public static final int OPER = 161;

    /** {@code SDL_SCANCODE_CLEARAGAIN}. */
    public static final int CLEARAGAIN = 162;

    /** {@code SDL_SCANCODE_CRSEL}. */
    public static final int CRSEL = 163;

    /** {@code SDL_SCANCODE_EXSEL}. */
    public static final int EXSEL = 164;

    /** {@code SDL_SCANCODE_FRONT}. */
    public static final int FRONT = 165;

    /** {@code SDL_SCANCODE_KP_00}. */
    public static final int KP_00 = 176;

    /** {@code SDL_SCANCODE_KP_000}. */
    public static final int KP_000 = 177;

    /** {@code SDL_SCANCODE_THOUSANDSSEPARATOR}. */
    public static final int THOUSANDSSEPARATOR = 178;

    /** {@code SDL_SCANCODE_DECIMALSEPARATOR}. */
    public static final int DECIMALSEPARATOR = 179;

    /** {@code SDL_SCANCODE_CURRENCYUNIT}. */
    public static final int CURRENCYUNIT = 180;

    /** {@code SDL_SCANCODE_CURRENCYSUBUNIT}. */
    public static final int CURRENCYSUBUNIT = 181;

    /** {@code SDL_SCANCODE_KP_LEFTPAREN}. */
    public static final int KP_LEFTPAREN = 182;

    /** {@code SDL_SCANCODE_KP_RIGHTPAREN}. */
    public static final int KP_RIGHTPAREN = 183;

    /** {@code SDL_SCANCODE_KP_LEFTBRACE}. */
    public static final int KP_LEFTBRACE = 184;

    /** {@code SDL_SCANCODE_KP_RIGHTBRACE}. */
    public static final int KP_RIGHTBRACE = 185;

    /** {@code SDL_SCANCODE_KP_TAB}. */
    public static final int KP_TAB = 186;

    /** {@code SDL_SCANCODE_KP_BACKSPACE}. */
    public static final int KP_BACKSPACE = 187;

    /** {@code SDL_SCANCODE_KP_A}. */
    public static final int KP_A = 188;

    /** {@code SDL_SCANCODE_KP_B}. */
    public static final int KP_B = 189;

    /** {@code SDL_SCANCODE_KP_C}. */
    public static final int KP_C = 190;

    /** {@code SDL_SCANCODE_KP_D}. */
    public static final int KP_D = 191;

    /** {@code SDL_SCANCODE_KP_E}. */
    public static final int KP_E = 192;

    /** {@code SDL_SCANCODE_KP_F}. */
    public static final int KP_F = 193;

    /** {@code SDL_SCANCODE_KP_XOR}. */
    public static final int KP_XOR = 194;

    /** {@code SDL_SCANCODE_KP_POWER}. */
    public static final int KP_POWER = 195;

    /** {@code SDL_SCANCODE_KP_PERCENT}. */
    public static final int KP_PERCENT = 196;

    /** {@code SDL_SCANCODE_KP_LESS}. */
    public static final int KP_LESS = 197;

    /** {@code SDL_SCANCODE_KP_GREATER}. */
    public static final int KP_GREATER = 198;

    /** {@code SDL_SCANCODE_KP_AMPERSAND}. */
    public static final int KP_AMPERSAND = 199;

    /** {@code SDL_SCANCODE_KP_DBLAMPERSAND}. */
    public static final int KP_DBLAMPERSAND = 200;

    /** {@code SDL_SCANCODE_KP_VERTICALBAR}. */
    public static final int KP_VERTICALBAR = 201;

    /** {@code SDL_SCANCODE_KP_DBLVERTICALBAR}. */
    public static final int KP_DBLVERTICALBAR = 202;

    /** {@code SDL_SCANCODE_KP_COLON}. */
    public static final int KP_COLON = 203;

    /** {@code SDL_SCANCODE_KP_HASH}. */
    public static final int KP_HASH = 204;

    /** {@code SDL_SCANCODE_KP_SPACE}. */
    public static final int KP_SPACE = 205;

    /** {@code SDL_SCANCODE_KP_AT}. */
    public static final int KP_AT = 206;

    /** {@code SDL_SCANCODE_KP_EXCLAM}. */
    public static final int KP_EXCLAM = 207;

    /** {@code SDL_SCANCODE_KP_MEMSTORE}. */
    public static final int KP_MEMSTORE = 208;

    /** {@code SDL_SCANCODE_KP_MEMRECALL}. */
    public static final int KP_MEMRECALL = 209;

    /** {@code SDL_SCANCODE_KP_MEMCLEAR}. */
    public static final int KP_MEMCLEAR = 210;

    /** {@code SDL_SCANCODE_KP_MEMADD}. */
    public static final int KP_MEMADD = 211;

    /** {@code SDL_SCANCODE_KP_MEMSUBTRACT}. */
    public static final int KP_MEMSUBTRACT = 212;

    /** {@code SDL_SCANCODE_KP_MEMMULTIPLY}. */
    public static final int KP_MEMMULTIPLY = 213;

    /** {@code SDL_SCANCODE_KP_MEMDIVIDE}. */
    public static final int KP_MEMDIVIDE = 214;

    /** {@code SDL_SCANCODE_KP_PLUSMINUS}. */
    public static final int KP_PLUSMINUS = 215;

    /** {@code SDL_SCANCODE_KP_CLEAR}. */
    public static final int KP_CLEAR = 216;

    /** {@code SDL_SCANCODE_KP_CLEARENTRY}. */
    public static final int KP_CLEARENTRY = 217;

    /** {@code SDL_SCANCODE_KP_BINARY}. */
    public static final int KP_BINARY = 218;

    /** {@code SDL_SCANCODE_KP_OCTAL}. */
    public static final int KP_OCTAL = 219;

    /** {@code SDL_SCANCODE_KP_DECIMAL}. */
    public static final int KP_DECIMAL = 220;

    /** {@code SDL_SCANCODE_KP_HEXADECIMAL}. */
    public static final int KP_HEXADECIMAL = 221;

    /** {@code SDL_SCANCODE_LCTRL}. */
    public static final int LCTRL = 224;

    /** {@code SDL_SCANCODE_LSHIFT}. */
    public static final int LSHIFT = 225;

    /** {@code SDL_SCANCODE_LALT}. */
    public static final int LALT = 226;

    /** {@code SDL_SCANCODE_LGUI}. */
    public static final int LGUI = 227;

    /** {@code SDL_SCANCODE_RCTRL}. */
    public static final int RCTRL = 228;

    /** {@code SDL_SCANCODE_RSHIFT}. */
    public static final int RSHIFT = 229;

    /** {@code SDL_SCANCODE_RALT}. */
    public static final int RALT = 230;

    /** {@code SDL_SCANCODE_RGUI}. */
    public static final int RGUI = 231;

    /** {@code SDL_SCANCODE_MODE}. */
    public static final int MODE = 257;

    /** {@code SDL_SCANCODE_SLEEP}. */
    public static final int SLEEP = 258;

    /** {@code SDL_SCANCODE_WAKE}. */
    public static final int WAKE = 259;

    /** {@code SDL_SCANCODE_CHANNEL_INCREMENT}. */
    public static final int CHANNEL_INCREMENT = 260;

    /** {@code SDL_SCANCODE_CHANNEL_DECREMENT}. */
    public static final int CHANNEL_DECREMENT = 261;

    /** {@code SDL_SCANCODE_MEDIA_PLAY}. */
    public static final int MEDIA_PLAY = 262;

    /** {@code SDL_SCANCODE_MEDIA_PAUSE}. */
    public static final int MEDIA_PAUSE = 263;

    /** {@code SDL_SCANCODE_MEDIA_RECORD}. */
    public static final int MEDIA_RECORD = 264;

    /** {@code SDL_SCANCODE_MEDIA_FAST_FORWARD}. */
    public static final int MEDIA_FAST_FORWARD = 265;

    /** {@code SDL_SCANCODE_MEDIA_REWIND}. */
    public static final int MEDIA_REWIND = 266;

    /** {@code SDL_SCANCODE_MEDIA_NEXT_TRACK}. */
    public static final int MEDIA_NEXT_TRACK = 267;

    /** {@code SDL_SCANCODE_MEDIA_PREVIOUS_TRACK}. */
    public static final int MEDIA_PREVIOUS_TRACK = 268;

    /** {@code SDL_SCANCODE_MEDIA_STOP}. */
    public static final int MEDIA_STOP = 269;

    /** {@code SDL_SCANCODE_MEDIA_EJECT}. */
    public static final int MEDIA_EJECT = 270;

    /** {@code SDL_SCANCODE_MEDIA_PLAY_PAUSE}. */
    public static final int MEDIA_PLAY_PAUSE = 271;

    /** {@code SDL_SCANCODE_MEDIA_SELECT}. */
    public static final int MEDIA_SELECT = 272;

    /** {@code SDL_SCANCODE_AC_NEW}. */
    public static final int AC_NEW = 273;

    /** {@code SDL_SCANCODE_AC_OPEN}. */
    public static final int AC_OPEN = 274;

    /** {@code SDL_SCANCODE_AC_CLOSE}. */
    public static final int AC_CLOSE = 275;

    /** {@code SDL_SCANCODE_AC_EXIT}. */
    public static final int AC_EXIT = 276;

    /** {@code SDL_SCANCODE_AC_SAVE}. */
    public static final int AC_SAVE = 277;

    /** {@code SDL_SCANCODE_AC_PRINT}. */
    public static final int AC_PRINT = 278;

    /** {@code SDL_SCANCODE_AC_PROPERTIES}. */
    public static final int AC_PROPERTIES = 279;

    /** {@code SDL_SCANCODE_AC_SEARCH}. */
    public static final int AC_SEARCH = 280;

    /** {@code SDL_SCANCODE_AC_HOME}. */
    public static final int AC_HOME = 281;

    /** {@code SDL_SCANCODE_AC_BACK}. */
    public static final int AC_BACK = 282;

    /** {@code SDL_SCANCODE_AC_FORWARD}. */
    public static final int AC_FORWARD = 283;

    /** {@code SDL_SCANCODE_AC_STOP}. */
    public static final int AC_STOP = 284;

    /** {@code SDL_SCANCODE_AC_REFRESH}. */
    public static final int AC_REFRESH = 285;

    /** {@code SDL_SCANCODE_AC_BOOKMARKS}. */
    public static final int AC_BOOKMARKS = 286;

    /** {@code SDL_SCANCODE_SOFTLEFT}. */
    public static final int SOFTLEFT = 287;

    /** {@code SDL_SCANCODE_SOFTRIGHT}. */
    public static final int SOFTRIGHT = 288;

    /** {@code SDL_SCANCODE_CALL}. */
    public static final int CALL = 289;

    /** {@code SDL_SCANCODE_ENDCALL}. */
    public static final int ENDCALL = 290;

    private static final String[] NAMES = new String[COUNT];
    private static final java.util.Map<String, Integer> BY_NAME = new java.util.HashMap<>();

    static {
        put(0, "UNKNOWN");
        put(4, "A");
        put(5, "B");
        put(6, "C");
        put(7, "D");
        put(8, "E");
        put(9, "F");
        put(10, "G");
        put(11, "H");
        put(12, "I");
        put(13, "J");
        put(14, "K");
        put(15, "L");
        put(16, "M");
        put(17, "N");
        put(18, "O");
        put(19, "P");
        put(20, "Q");
        put(21, "R");
        put(22, "S");
        put(23, "T");
        put(24, "U");
        put(25, "V");
        put(26, "W");
        put(27, "X");
        put(28, "Y");
        put(29, "Z");
        put(30, "1");
        put(31, "2");
        put(32, "3");
        put(33, "4");
        put(34, "5");
        put(35, "6");
        put(36, "7");
        put(37, "8");
        put(38, "9");
        put(39, "0");
        put(40, "RETURN");
        put(41, "ESCAPE");
        put(42, "BACKSPACE");
        put(43, "TAB");
        put(44, "SPACE");
        put(45, "MINUS");
        put(46, "EQUALS");
        put(47, "LEFTBRACKET");
        put(48, "RIGHTBRACKET");
        put(49, "BACKSLASH");
        put(50, "NONUSHASH");
        put(51, "SEMICOLON");
        put(52, "APOSTROPHE");
        put(53, "GRAVE");
        put(54, "COMMA");
        put(55, "PERIOD");
        put(56, "SLASH");
        put(57, "CAPSLOCK");
        put(58, "F1");
        put(59, "F2");
        put(60, "F3");
        put(61, "F4");
        put(62, "F5");
        put(63, "F6");
        put(64, "F7");
        put(65, "F8");
        put(66, "F9");
        put(67, "F10");
        put(68, "F11");
        put(69, "F12");
        put(70, "PRINTSCREEN");
        put(71, "SCROLLLOCK");
        put(72, "PAUSE");
        put(73, "INSERT");
        put(74, "HOME");
        put(75, "PAGEUP");
        put(76, "DELETE");
        put(77, "END");
        put(78, "PAGEDOWN");
        put(79, "RIGHT");
        put(80, "LEFT");
        put(81, "DOWN");
        put(82, "UP");
        put(83, "NUMLOCKCLEAR");
        put(84, "KP_DIVIDE");
        put(85, "KP_MULTIPLY");
        put(86, "KP_MINUS");
        put(87, "KP_PLUS");
        put(88, "KP_ENTER");
        put(89, "KP_1");
        put(90, "KP_2");
        put(91, "KP_3");
        put(92, "KP_4");
        put(93, "KP_5");
        put(94, "KP_6");
        put(95, "KP_7");
        put(96, "KP_8");
        put(97, "KP_9");
        put(98, "KP_0");
        put(99, "KP_PERIOD");
        put(100, "NONUSBACKSLASH");
        put(101, "APPLICATION");
        put(102, "POWER");
        put(103, "KP_EQUALS");
        put(104, "F13");
        put(105, "F14");
        put(106, "F15");
        put(107, "F16");
        put(108, "F17");
        put(109, "F18");
        put(110, "F19");
        put(111, "F20");
        put(112, "F21");
        put(113, "F22");
        put(114, "F23");
        put(115, "F24");
        put(116, "EXECUTE");
        put(117, "HELP");
        put(118, "MENU");
        put(119, "SELECT");
        put(120, "STOP");
        put(121, "AGAIN");
        put(122, "UNDO");
        put(123, "CUT");
        put(124, "COPY");
        put(125, "PASTE");
        put(126, "FIND");
        put(127, "MUTE");
        put(128, "VOLUMEUP");
        put(129, "VOLUMEDOWN");
        put(130, "LOCKINGCAPSLOCK");
        put(131, "LOCKINGNUMLOCK");
        put(132, "LOCKINGSCROLLLOCK");
        put(133, "KP_COMMA");
        put(134, "KP_EQUALSAS400");
        put(135, "INTERNATIONAL1");
        put(136, "INTERNATIONAL2");
        put(137, "INTERNATIONAL3");
        put(138, "INTERNATIONAL4");
        put(139, "INTERNATIONAL5");
        put(140, "INTERNATIONAL6");
        put(141, "INTERNATIONAL7");
        put(142, "INTERNATIONAL8");
        put(143, "INTERNATIONAL9");
        put(144, "LANG1");
        put(145, "LANG2");
        put(146, "LANG3");
        put(147, "LANG4");
        put(148, "LANG5");
        put(149, "LANG6");
        put(150, "LANG7");
        put(151, "LANG8");
        put(152, "LANG9");
        put(153, "ALTERASE");
        put(154, "SYSREQ");
        put(155, "CANCEL");
        put(156, "CLEAR");
        put(157, "PRIOR");
        put(158, "RETURN2");
        put(159, "SEPARATOR");
        put(160, "OUT");
        put(161, "OPER");
        put(162, "CLEARAGAIN");
        put(163, "CRSEL");
        put(164, "EXSEL");
        put(165, "FRONT");
        put(176, "KP_00");
        put(177, "KP_000");
        put(178, "THOUSANDSSEPARATOR");
        put(179, "DECIMALSEPARATOR");
        put(180, "CURRENCYUNIT");
        put(181, "CURRENCYSUBUNIT");
        put(182, "KP_LEFTPAREN");
        put(183, "KP_RIGHTPAREN");
        put(184, "KP_LEFTBRACE");
        put(185, "KP_RIGHTBRACE");
        put(186, "KP_TAB");
        put(187, "KP_BACKSPACE");
        put(188, "KP_A");
        put(189, "KP_B");
        put(190, "KP_C");
        put(191, "KP_D");
        put(192, "KP_E");
        put(193, "KP_F");
        put(194, "KP_XOR");
        put(195, "KP_POWER");
        put(196, "KP_PERCENT");
        put(197, "KP_LESS");
        put(198, "KP_GREATER");
        put(199, "KP_AMPERSAND");
        put(200, "KP_DBLAMPERSAND");
        put(201, "KP_VERTICALBAR");
        put(202, "KP_DBLVERTICALBAR");
        put(203, "KP_COLON");
        put(204, "KP_HASH");
        put(205, "KP_SPACE");
        put(206, "KP_AT");
        put(207, "KP_EXCLAM");
        put(208, "KP_MEMSTORE");
        put(209, "KP_MEMRECALL");
        put(210, "KP_MEMCLEAR");
        put(211, "KP_MEMADD");
        put(212, "KP_MEMSUBTRACT");
        put(213, "KP_MEMMULTIPLY");
        put(214, "KP_MEMDIVIDE");
        put(215, "KP_PLUSMINUS");
        put(216, "KP_CLEAR");
        put(217, "KP_CLEARENTRY");
        put(218, "KP_BINARY");
        put(219, "KP_OCTAL");
        put(220, "KP_DECIMAL");
        put(221, "KP_HEXADECIMAL");
        put(224, "LCTRL");
        put(225, "LSHIFT");
        put(226, "LALT");
        put(227, "LGUI");
        put(228, "RCTRL");
        put(229, "RSHIFT");
        put(230, "RALT");
        put(231, "RGUI");
        put(257, "MODE");
        put(258, "SLEEP");
        put(259, "WAKE");
        put(260, "CHANNEL_INCREMENT");
        put(261, "CHANNEL_DECREMENT");
        put(262, "MEDIA_PLAY");
        put(263, "MEDIA_PAUSE");
        put(264, "MEDIA_RECORD");
        put(265, "MEDIA_FAST_FORWARD");
        put(266, "MEDIA_REWIND");
        put(267, "MEDIA_NEXT_TRACK");
        put(268, "MEDIA_PREVIOUS_TRACK");
        put(269, "MEDIA_STOP");
        put(270, "MEDIA_EJECT");
        put(271, "MEDIA_PLAY_PAUSE");
        put(272, "MEDIA_SELECT");
        put(273, "AC_NEW");
        put(274, "AC_OPEN");
        put(275, "AC_CLOSE");
        put(276, "AC_EXIT");
        put(277, "AC_SAVE");
        put(278, "AC_PRINT");
        put(279, "AC_PROPERTIES");
        put(280, "AC_SEARCH");
        put(281, "AC_HOME");
        put(282, "AC_BACK");
        put(283, "AC_FORWARD");
        put(284, "AC_STOP");
        put(285, "AC_REFRESH");
        put(286, "AC_BOOKMARKS");
        put(287, "SOFTLEFT");
        put(288, "SOFTRIGHT");
        put(289, "CALL");
        put(290, "ENDCALL");
    }

    private static void put(int code, String name) {
        NAMES[code] = name;
        BY_NAME.put(name, code);
    }

    /** Returns the stable name of {@code scancode}, or {@code null} if it has none. */
    public static String name(int scancode) {
        return scancode >= 0 && scancode < COUNT ? NAMES[scancode] : null;
    }

    /** Returns the scancode for a {@link #name(int)} (case-insensitive), or {@link #UNKNOWN}. */
    public static int fromName(String name) {
        Integer code = BY_NAME.get(name.toUpperCase(java.util.Locale.ROOT));
        return code == null ? UNKNOWN : code;
    }
}

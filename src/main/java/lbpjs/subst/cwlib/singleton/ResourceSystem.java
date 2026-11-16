package lbpjs.subst.cwlib.singleton;

import cwlib.resources.RTranslationTable;

/**
 * Stand-in for cwlib.singleton.ResourceSystem in the TeaVM build. The real one
 * holds the toolkit's open archives, databases and Swing selection; a web page
 * has none of those, so lookups find nothing. Log messages are dropped unless
 * LOG_LEVEL is raised (1 = info, 5 = serializer trace).
 */
public final class ResourceSystem
{
    public static boolean GUI_MODE = false;
    public static int LOG_LEVEL = 0;

    private ResourceSystem() { }

    public static void println(Object message)
    {
        if (message == null || LOG_LEVEL < 1) return;
        System.out.println("[ResourceSystem] " + message);
    }

    public static void println(String channel, Object message)
    {
        if (message == null || LOG_LEVEL < 1) return;
        System.out.println("[" + channel + "] " + message);
    }

    public static RTranslationTable getLAMS() { return null; }
}

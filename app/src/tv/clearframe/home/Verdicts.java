package tv.clearframe.home;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Which apps are safe to turn off. Lists and names from TV Detox's candidates.sh
// (github.com/Ansub/tv-detox, MIT), which credits tvslim.app and tvdebloat.com for the Philips
// entries. Sony-specific apps aren't in these lists, so on a BRAVIA they show as "review".
final class Verdicts {
    static final String NEVER = "never", KEEP = "keep", SAFE = "safe", REVIEW = "review";

    // The TV needs these to start, or to keep working once it has.
    private static final Set<String> NEVER_SET = new HashSet<>(Arrays.asList(
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.android.tv.settings", "com.android.settings", "com.android.systemui",
            "com.android.providers.settings", "com.android.settings.intelligence", "com.android.shell",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller", "com.android.bluetooth", "com.android.externalstorage",
            "com.android.providers.media", "com.android.providers.media.module", "com.android.providers.downloads",
            "com.android.keychain", "com.android.inputdevices", "com.android.location.fused", "com.android.se",
            "com.android.providers.tv", "com.android.providers.contacts", "com.android.providers.calendar",
            "com.android.networkstack.inprocess", "com.android.networkstack.permissionconfig",
            "com.android.certinstaller", "com.android.companiondevicemanager", "com.android.dynsystem",
            "com.android.statementservice", "com.android.vpndialogs", "com.android.wallpaperbackup",
            "com.android.backupconfirm", "com.android.sharedstoragebackup", "com.android.localtransport",
            "com.android.proxyhandler", "com.android.pacprocessor", "com.android.captiveportallogin",
            "com.dolby.android.audio.service",
            "com.google.android.inputmethod.latin", "com.android.inputmethod.latin", "com.google.android.leanback.ime",
            "com.tcl.inputmethod.international", "com.android.providers.userdictionary",
            "com.google.android.tungsten.setupwraith", "com.google.android.onetimeinitializer",
            "com.google.android.partnersetup", "com.android.provision", "com.android.tv.provision",
            "com.android.managedprovisioning",
            "com.tcl.suspension", "com.tcl.tvinput", "com.tcl.providers.config", "com.tcl.useragreement",
            "com.tcl.settings", "com.tcl.tcl_bt_rcu_service", "com.google.android.apps.tv.launcherx",
            "org.droidtv.homeintentresolver", "org.droidtv.GlobalKey", "org.droidtv.playtv",
            "org.droidtv.hdmiservice", "org.droidtv.channels", "org.droidtv.tvsystemui",
            "org.droidtv.tvpowermanagerservice", "org.droidtv.tv.tptvservice",
            "org.droidtv.lkb", "org.droidtv.xtv", "org.droidtv.settings",
            "org.droidtv.menudataapp", "org.droidtv.eum", "org.droidtv.aurora",
            // and this home screen
            "tv.clearframe.home"));

    // Turn off only if you don't use the feature they carry.
    private static final Set<String> KEEP_SET = new HashSet<>(Arrays.asList(
            "com.google.android.apps.mediashell", "com.google.android.katniss",
            "com.google.android.tv.assistant", "com.google.android.marvin.talkback",
            "com.google.android.tv.remote.service", "com.google.android.tts",
            "com.google.android.syncadapters.contacts", "com.google.android.syncadapters.calendar",
            "com.google.android.webview", "com.tcl.android.webview",
            "com.tcl.tv", "com.tcl.micmanager",
            "com.android.dreams.basic", "com.google.android.apps.tv.dreamx"));

    // Commonly preinstalled junk: ads, sponsored rows, unused vendor and Google apps.
    private static final Set<String> SAFE_SET = new HashSet<>(Arrays.asList(
            "com.google.android.tvrecommendations", "com.google.android.backdrop",
            "com.google.android.videos", "com.google.android.youtube.tvmusic", "com.google.android.play.games",
            "com.google.android.feedback",
            "com.android.printspooler", "com.android.htmlviewer", "com.android.soundpicker",
            "com.tcl.waterfall.overseas", "com.tcl.esticker", "com.tcl.partnercustomizer",
            "com.tcl.messagebox", "com.tcl.smartalexa", "com.tcl.copydatatotv", "com.tcl.audioplayer",
            "com.tcl.imageplayer", "com.tcl.keyhelp", "com.tcl.usercenter",
            "com.tcl.dashboard", "com.tcl.ttvs", "com.tcl.factory.view"));

    // Stock home screens. Turning one off is what lets Home open ClearFrame; it's handled by the
    // "Make ClearFrame the Home screen" step, not the app list. From TV Detox's install.sh.
    static final Set<String> LAUNCHERS = new HashSet<>(Arrays.asList(
            "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
            "com.google.android.apps.tv.launcherx", "com.mitv.tvhome", "com.amazon.tv.launcher"));

    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        String[] n = {
                "com.google.android.gms", "Google Play services",
                "com.google.android.gsf", "Google services framework",
                "com.android.vending", "Google Play Store",
                "com.android.tv.settings", "TV Settings",
                "com.android.settings", "Settings",
                "com.android.systemui", "System UI",
                "com.android.bluetooth", "Bluetooth",
                "com.google.android.apps.mediashell", "Google Cast, casting from your phone",
                "com.google.android.katniss", "Google search and voice",
                "com.google.android.tv.assistant", "Google Assistant",
                "com.google.android.marvin.talkback", "TalkBack screen reader",
                "com.google.android.tv.remote.service", "Phone remote control",
                "com.google.android.inputmethod.latin", "Gboard keyboard",
                "com.android.inputmethod.latin", "Android keyboard",
                "com.google.android.leanback.ime", "TV keyboard",
                "com.google.android.tts", "Text to speech",
                "com.google.android.tvrecommendations", "Google TV sponsored row",
                "com.google.android.backdrop", "Google TV backdrop ads",
                "com.google.android.videos", "Google TV, Play Movies",
                "com.google.android.youtube.tvmusic", "YouTube Music",
                "com.google.android.play.games", "Play Games",
                "com.google.android.youtube.tv", "YouTube",
                "com.google.android.feedback", "Google feedback",
                "com.google.android.onetimeinitializer", "One-time setup helper",
                "com.google.android.partnersetup", "Partner app setup",
                "com.google.android.syncadapters.contacts", "Contacts sync",
                "com.google.android.syncadapters.calendar", "Calendar sync",
                "com.android.printspooler", "Printing",
                "com.android.dreams.basic", "Basic screensaver",
                "com.google.android.apps.tv.dreamx", "Google TV screensaver",
                "com.android.htmlviewer", "HTML file viewer",
                "com.netflix.ninja", "Netflix",
                "com.amazon.amazonvideo.livingroom", "Prime Video",
                "com.google.android.tvlauncher", "Android TV home screen",
                "com.google.android.apps.tv.launcherx", "Google TV home screen",
                "com.google.android.leanbacklauncher", "Android TV home screen (older)",
                "com.google.android.tungsten.setupwraith", "Google TV setup",
                "com.dolby.android.audio.service", "Dolby audio",
                "com.google.android.webview", "Web pages inside apps",
                "com.android.providers.userdictionary", "Keyboard dictionary",
                "tv.clearframe.home", "ClearFrame (this home screen)",
        };
        for (int i = 0; i < n.length; i += 2) NAMES.put(n[i], n[i + 1]);
    }

    static String verdict(String pkg, boolean persistent) {
        if (persistent || NEVER_SET.contains(pkg)) return NEVER;
        if (KEEP_SET.contains(pkg)) return KEEP;
        if (SAFE_SET.contains(pkg)) return SAFE;
        return REVIEW;
    }

    static String name(String pkg) { return NAMES.get(pkg); }

    static int order(String verdict) {
        switch (verdict) {
            case SAFE: return 0;
            case REVIEW: return 1;
            case KEEP: return 2;
            default: return 3;
        }
    }

    private Verdicts() { }
}

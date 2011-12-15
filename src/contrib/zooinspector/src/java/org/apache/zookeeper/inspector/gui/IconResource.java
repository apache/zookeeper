package org.apache.zookeeper.inspector.gui;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import javax.swing.ImageIcon;

import org.apache.zookeeper.inspector.logger.LoggerFactory;

/**
 * @see http://standards.freedesktop.org/icon-naming-spec/icon-naming-spec-latest.html
 * I tried to take icons that are available in the Tango icon set
 */
public class IconResource {

    public static final String ICON_ChangeNodeViewers = "";
    public static final String ICON_TREE_LEAF = "mimetypes/text-x-generic";
    public static final String ICON_TREE_OPEN = "places/folder";
    public static final String ICON_TREE_CLOSE = "places/folder";
    public static final String ICON_INFORMATION = "status/info";
    public static final String ICON_SAVE = "actions/document-save";
    public static final String ICON_UP = "actions/up";
    public static final String ICON_DOWN = "actions/down";
    public static final String ICON_ADD = "actions/add";
    public static final String ICON_REMOVE = "actions/remove";
    public static final String ICON_START = "actions/media-playback-start";
    public static final String ICON_STOP = "actions/media-playback-stop";
    public static final String ICON_DOCUMENT_ADD = "actions/document-new";
    public static final String ICON_REFRESH = "actions/view-refresh";
    public static final String ICON_TRASH = "places/user-trash";
    // better: actions/help-about, but not in tango
    public static final String ICON_HELP_ABOUT = "status/info";

    private static final String DEFAULT_THEME = "Tango";
    private static final String DEFAULT_SIZE = "16x16";
    private static final String FALLBACK_ICON = "face-surprise";

    // compare http://standards.freedesktop.org/basedir-spec/basedir-spec-latest.html
    private static final String[] DEFAULT_XDG_DATA_DIRS = new String[]{
        "/usr/local/share",
        "/usr/share"
    };

    private String theme = DEFAULT_THEME;
    private String size = DEFAULT_SIZE;

    public URL find(String name) {
        String iconPath = buildIconPath(name);
        URL iconUrl = findInPaths(iconPath);
        if(null != iconUrl) return iconUrl;

        iconUrl = getClass().getResource(iconPath);
        if(null != iconUrl) return iconUrl;

        if(!name.equals(FALLBACK_ICON)) return find(FALLBACK_ICON);
        return null;
    }

    public ImageIcon get(String name, String description) {
        URL iconUrl = find(name);
        if(null==iconUrl) {
            ImageIcon icon = new ImageIcon();
            icon.setDescription(description);
            return icon;
        } else {
            return new ImageIcon(iconUrl, description);
        }
    }

    private URL findInPaths(String iconPath) {
        for(String dataDir : DEFAULT_XDG_DATA_DIRS) {
            File file = new File(dataDir + iconPath);
            if(file.exists()) {
                try {
                    return file.toURI().toURL();
                } catch (MalformedURLException e) {
                    LoggerFactory.getLogger().warn(e.toString());
                }
            }
        }
        return null;
    }

    private String buildIconPath(String name) {
        return "/icons/" + theme + "/" + size + "/" + name + ".png";
    }
}

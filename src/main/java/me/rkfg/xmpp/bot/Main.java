package me.rkfg.xmpp.bot;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.rkfg.xmpp.bot.plugins.MessagePlugin;

import org.apache.commons.lang3.StringEscapeUtils;
import org.jivesoftware.smack.AbstractConnectionListener;
import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManager;
import org.jivesoftware.smack.ChatManagerListener;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.IQTypeFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.iqversion.packet.Version;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.ping.PingFailedListener;
import org.jivesoftware.smackx.ping.PingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.ppsrk.gwt.server.HibernateUtil;
import ru.ppsrk.gwt.server.SettingsManager;

public class Main {

    private static final String PLUGINS_PACKAGE_NAME = "me.rkfg.xmpp.bot.plugins.";
    private static Logger log = LoggerFactory.getLogger(Main.class);
    private static String nick;
    private static MUCManager mucManager = new MUCManager();
    private static SettingsManager sm = SettingsManager.getInstance();
    private static ExecutorService outgoingMsgsExecutor = Executors.newSingleThreadExecutor();
    private static List<MessagePlugin> plugins = new LinkedList<MessagePlugin>();
    private static ExecutorService commandExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);

    public static void main(String[] args) throws InterruptedException, SmackException, IOException {
        log.info("Starting up...");
        sm.setFilename("settings.ini");
        try {
            sm.loadSettings();
        } catch (FileNotFoundException e) {
            log.warn("settings.ini not found!", e);
            return;
        } catch (IOException e) {
            log.warn("settings.ini can't be read!", e);
            return;
        }
        sm.setDefault("nick", "Talho-san");
        sm.setDefault("login", "talho");
        sm.setDefault("resource", "jbot");
        sm.setDefault("usedb", "0");
        if (sm.getIntegerSetting("usedb") != 0) {
            HibernateUtil.initSessionFactory("hibernate.cfg.xml");
        }

        nick = sm.getStringSetting("nick");
        String pluginClasses = sm.getStringSetting("plugins");
        loadPlugins(pluginClasses);
        log.info("Plugins loaded, initializing...");
        for (MessagePlugin plugin : plugins) {
            plugin.init();
        }
        log.info("Plugins initializion complete.");

        final XMPPConnection connection = new XMPPTCPConnection(sm.getStringSetting("server"));
        try {
            connection.connect();
            connection.login(sm.getStringSetting("login"), sm.getStringSetting("password"), sm.getStringSetting("resource"));
        } catch (XMPPException e) {
            log.warn("Connection error: ", e);
            return;
        }
        final String[] mucs = org.apache.commons.lang3.StringUtils.split(sm.getStringSetting("join"), ',');
        joinMUCs(connection, mucs);
        connection.addConnectionListener(new AbstractConnectionListener() {
            @Override
            public void reconnectionSuccessful() {
                log.warn("Reconnected, rejoining mucs.", org.apache.commons.lang3.StringUtils.join((Object[]) mucs, ", "));
                try {
                    joinMUCs(connection, mucs);
                } catch (NotConnectedException e) {
                    log.warn("Not connected while rejoining: ", e);
                }
            }
        });
        ChatManager.getInstanceFor(connection).addChatListener(new ChatManagerListener() {

            @Override
            public void chatCreated(Chat chat, boolean createdLocally) {
                chat.addMessageListener(new MessageListener() {

                    @Override
                    public void processMessage(Chat chat, Message message) {
                        Main.processMessage(new ChatAdapterImpl(chat), message);
                    }
                });
            }
        });

        connection.addPacketListener(new PacketListener() {

            @Override
            public void processPacket(Packet packet) {
                Version version = new Version("Gekko-go console", "14.7", "Nirvash OpenFirmware v7.1");
                version.setFrom(packet.getTo());
                version.setTo(packet.getFrom());
                version.setType(Type.RESULT);
                version.setPacketID(packet.getPacketID());
                try {
                    connection.sendPacket(version);
                } catch (NotConnectedException e) {
                    e.printStackTrace();
                }
            }
        }, new AndFilter(new IQTypeFilter(Type.GET), new PacketTypeFilter(Version.class)));
        log.info("Sub req: {}", connection.getRoster().getSubscriptionMode());
        final PingManager pingManager = PingManager.getInstanceFor(connection);
        pingManager.setPingInterval(10);
        pingManager.registerPingFailedListener(new PingFailedListener() {

            @Override
            public void pingFailed() {
                pingManager.setPingInterval(10);
            }
        });
        while (true) {
            Thread.sleep(1000);
        }
    }

    private static void joinMUCs(final XMPPConnection connection, String[] mucs) throws NotConnectedException {
        mucManager.leave();
        for (String conf : mucs) {
            mucManager.join(connection, conf, nick);
        }
    }

    private static void loadPlugins(String pluginClassesNamesStr) {
        String[] pluginClassesNames = pluginClassesNamesStr.split(",\\s?");
        log.debug("Plugins found: {}", (Object) pluginClassesNames);
        for (String pluginName : pluginClassesNames) {
            try {
                Class<? extends MessagePlugin> clazz = Class.forName(PLUGINS_PACKAGE_NAME + pluginName).asSubclass(MessagePlugin.class);
                plugins.add(clazz.newInstance());
            } catch (ClassNotFoundException e) {
                log.warn("Couldn't load plugin {}: {}", pluginName, e);
            } catch (InstantiationException e) {
                log.warn("Couldn't load plugin {}: {}", pluginName, e);
            } catch (IllegalAccessException e) {
                log.warn("Couldn't load plugin {}: {}", pluginName, e);
            }
        }
    }

    public static void processMessage(final ChatAdapter chat, final Message message) {
        commandExecutor.submit(new Runnable() {

            @Override
            public void run() {
                if (nick.equals(StringUtils.parseResource(message.getFrom()))) {
                    return;
                }
                if (message.getSubject() != null && !message.getSubject().isEmpty()) {
                    return;
                }
                String text = message.getBody();
                log.info("<{}>: {}", message.getFrom(), text);
                for (MessagePlugin plugin : plugins) {
                    Pattern pattern = plugin.getPattern();
                    if (pattern != null) {
                        Matcher matcher = pattern.matcher(text);
                        if (matcher.find()) {
                            try {
                                String result = plugin.process(message, matcher);
                                if (result != null && !result.isEmpty()) {
                                    sendMessage(chat, StringEscapeUtils.unescapeHtml4(result));
                                    break;
                                }
                            } catch (Throwable e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        });
    }

    public static void sendMessage(final ChatAdapter chatAdapter, final String message) {
        outgoingMsgsExecutor.execute(new Runnable() {

            @Override
            public void run() {
                try {
                    chatAdapter.sendMessage(message);
                    Thread.sleep(1000);
                } catch (XMPPException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (NotConnectedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public static String getNick() {
        return nick;
    }

    public static void sendMUCMessage(String message) {
        for (MUCParams mucParams : mucManager.listMUCParams()) {
            sendMessage(mucParams.getMucAdapted(), message);
        }
    }

    public static void sendMUCMessage(String message, String MUCName) {
        for (MultiUserChat multiUserChat : mucManager.listMUCs()) {
            if (multiUserChat.getRoom().equals(MUCName)) {
                sendMessage(mucManager.getMUCParams(multiUserChat).getMucAdapted(), message);
                return;
            }
        }
    }

    public static SettingsManager getSettingsManager() {
        return sm;
    }

    public static List<MessagePlugin> getPlugins() {
        return plugins;
    }

    public static MUCManager getMUCManager() {
        return mucManager;
    }
}

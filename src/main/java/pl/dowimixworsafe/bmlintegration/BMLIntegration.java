package pl.dowimixworsafe.bmlintegration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class BMLIntegration extends JavaPlugin {

    private PartyManager partyManager;

    @Override
    public void onEnable() {
        this.partyManager = new PartyManager(this);

        // Register the plugin messaging channels (outgoing/incoming).
        getServer().getMessenger().registerOutgoingPluginChannel(this, "bml:sync");

        BmlPluginMessageListener messageListener = new BmlPluginMessageListener(partyManager, getLogger());
        getServer().getMessenger().registerIncomingPluginChannel(this, "bml:sync", messageListener);

        // Register Bukkit event listeners.
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(partyManager), this);

        getLogger().info("BML Integration enabled. Listening on the bml:sync channel.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }
}

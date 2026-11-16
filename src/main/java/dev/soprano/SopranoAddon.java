package dev.soprano;

import dev.soprano.host.MeteorHost;
import dev.soprano.host.MeteorSopranoModule;
import dev.soprano.ui.SopranoCommand;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Loaded only when Meteor discovers the optional addon entrypoint. */
public final class SopranoAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Wayclef");

    @Override
    public void onInitialize() {
        MeteorHost host = new MeteorHost();
        var module = SopranoClient.initialize(host);
        var adapter = new MeteorSopranoModule(module);
        host.attach(adapter);
        Modules.get().add(adapter);
        Commands.add(new SopranoCommand(module));
        MeteorClient.EVENT_BUS.subscribe(host);
    }

    @Override public void onRegisterCategories() { Modules.registerCategory(CATEGORY); }
    @Override public String getPackage() { return "dev.soprano"; }
}

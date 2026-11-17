package com.dwinovo.numen.api.client.screen;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.client.agent.RemoteCompanions;
import com.dwinovo.numen.api.client.screen.settings.HostThemeColors;
import com.dwinovo.numen.api.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.api.client.ui.widget.Button;
import com.dwinovo.numen.api.client.ui.widget.Dropdown;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import com.dwinovo.numen.api.permission.DiscardPolicy;
import com.dwinovo.numen.api.permission.PermissionLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 每位伙伴独立的丢弃偏好表单;草稿只在保存且服务端确认后生效。 */
public final class DiscardSettingsScreen extends Screen {
    private final Screen parent;
    private final UUID companion;
    private final UiRoot ui = new UiRoot();
    private final Set<String> keep = new TreeSet<>(), discard = new TreeSet<>();
    private boolean wood = true, minerals = true, loaded, loading, saving, onlyProtected;
    private String query = "", message = "正在读取服务器设置…";
    private int left, top, panelWidth, panelHeight, page, pageSize, listTop;
    private List<Item> filtered = List.of();
    private EditBox search;

    public DiscardSettingsScreen(Screen parent, UUID companion, String name) {
        super(Component.literal(name + " · 丢弃保护"));
        this.parent = parent;
        this.companion = companion;
    }

    @Override protected void init() {
        panelWidth = Math.min(510, width - 16);
        panelHeight = Math.min(350, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listTop = top + 103;
        pageSize = Math.max(1, (panelHeight - 151) / 25);
        search = new EditBox(font, left + 12, top + 77, panelWidth - 24, 18, Component.literal("搜索物品"));
        search.setMaxLength(128);
        search.setBordered(false);
        search.setTextColor(UiTheme.current().text());
        search.setHint(Component.literal("搜索中文名称或物品 ID，如 橡木 / iron"));
        search.setValue(query);
        search.setResponder(text -> { query = text; page = 0; filter(); rebuild(); });
        addWidget(search);
        filter();
        rebuild();
        if (!loaded && !loading) {
            loading = true;
            RemoteCompanions.library().request(Libraries.PERMISSION, Libraries.LIST,
                new com.google.gson.JsonObject(), reply -> {
                    loading = false;
                    if (minecraft.screen != this) return;
                    if (!reply.ok()) { message = reply.problem(); rebuild(); return; }
                    for (var entry : RemoteCompanions.library().entries(Libraries.PERMISSION)) {
                        var row = entry.getAsJsonObject();
                        if (row.has("row") && "discard".equals(row.get("row").getAsString())
                                && companion.toString().equals(row.get("companion").getAsString())) {
                            apply(DiscardPolicy.fromJson(row, false));
                            loaded = true;
                            message = "仅当前伙伴生效；未保护物品允许按需丢弃，其他权限规则仍生效。";
                            filter(); rebuild(); return;
                        }
                    }
                    message = "服务器未提供该伙伴的丢弃设置，请确认两端都已更新。";
                    rebuild();
                });
        }
    }

    private DiscardPolicy draft() { return new DiscardPolicy(wood, minerals, keep, discard); }
    private void apply(DiscardPolicy policy) {
        wood = policy.wood(); minerals = policy.minerals();
        keep.clear(); keep.addAll(policy.keep());
        discard.clear(); discard.addAll(policy.discard());
    }
    private String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }
    private void filter() {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        DiscardPolicy policy = draft();
        List<Item> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || onlyProtected && !policy.protects(item)) continue;
            if (needle.isEmpty() || id(item).contains(needle)
                    || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(needle)) out.add(item);
        }
        filtered = List.copyOf(out);
        page = Math.clamp(page, 0, pages() - 1);
    }
    private int pages() { return Math.max(1, (filtered.size() + pageSize - 1) / pageSize); }
    private Button button(String text, int bx, int by, int bw, Runnable action) {
        Button b = ui.add(new Button(text, Button.Style.LINK, action));
        b.setBounds(bx, by, bw, 18);
        b.setEnabled(!saving && loaded);
        return b;
    }
    private void rebuild() {
        ui.clear();
        ui.setViewportHeight(height - 6);
        int half = (panelWidth - 28) / 2;
        button("木材资源：" + (wood ? "保护" : "允许丢弃"), left + 10, top + 29, half,
                () -> { wood = !wood; filter(); rebuild(); });
        button("矿石及矿产品：" + (minerals ? "保护" : "允许丢弃"), left + 16 + half, top + 29, half,
                () -> { minerals = !minerals; filter(); rebuild(); });
        button(onlyProtected ? "筛选：只看保护项" : "筛选：全部物品", left + 10, top + 51, half,
                () -> { onlyProtected = !onlyProtected; page = 0; filter(); rebuild(); });
        button("恢复默认清单", left + 16 + half, top + 51, half,
                () -> { apply(DiscardPolicy.DEFAULT); filter(); rebuild(); });
        int end = Math.min(filtered.size(), (page + 1) * pageSize);
        for (int at = page * pageSize; at < end; at++) {
            String id = id(filtered.get(at));
            int selected = keep.contains(id) ? 1 : discard.contains(id) ? 2 : 0;
            Dropdown pick = ui.add(new Dropdown(List.of("按分类", "始终保护", "允许丢弃"), selected,
                    choice -> select(id, choice)));
            pick.setBounds(left + panelWidth - 108, listTop + (at - page * pageSize) * 25 + 3, 96, 18);
            pick.setEnabled(loaded && !saving);
        }
        button("上一页", left + 8, top + panelHeight - 44, 62, () -> { page = Math.max(0, page - 1); rebuild(); });
        button("下一页", left + 76, top + panelHeight - 44, 62, () -> { page = Math.min(pages() - 1, page + 1); rebuild(); });
        Button cancel = button("取消", left + panelWidth - 114, top + panelHeight - 44, 48, this::onClose);
        cancel.setEnabled(!saving);
        button("保存", left + panelWidth - 62, top + panelHeight - 44, 50, this::save);
    }
    private void select(String id, int choice) {
        if (choice != 0 && !(choice == 1 ? keep : discard).contains(id)
                && (choice == 1 ? keep : discard).size() >= DiscardPolicy.MAX_EXCEPTIONS) {
            message = "单件例外已达上限，请先取消部分例外。";
            rebuild(); return;
        }
        keep.remove(id); discard.remove(id);
        if (choice == 1) keep.add(id);
        if (choice == 2) discard.add(id);
        filter(); rebuild();
    }
    private void save() {
        if (!loaded || saving) return;
        saving = true; message = "正在保存…"; rebuild();
        var body = draft().json();
        body.addProperty("companion", companion.toString());
        RemoteCompanions.library().request(Libraries.PERMISSION, PermissionLibrary.DISCARD, body, reply -> {
            saving = false;
            if (minecraft.screen != this) return;
            if (reply.ok()) minecraft.setScreen(parent);
            else { message = reply.problem(); rebuild(); }
        });
    }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        UiTheme t = UiTheme.current();
        g.fill(0, 0, width, height, 0xB0000000);
        g.fill(left, top, left + panelWidth, top + panelHeight, t.band());
        String pageInfo = (page + 1) + "/" + pages() + " 页 · " + filtered.size() + " 种";
        int infoWidth = font.width(pageInfo);
        Nb.text(g, font, Nb.clip(font, title.getString(), panelWidth - infoWidth - 36), left + 12, top + 11, t.text());
        Nb.text(g, font, pageInfo, left + panelWidth - infoWidth - 12, top + 11, t.textDim());
        g.fill(left + 10, top + 74, left + panelWidth - 10, top + 97, t.field());
        search.render(g, mx, my, partial);
        DiscardPolicy policy = draft();
        Item hovered = null;
        for (int at = page * pageSize; at < Math.min(filtered.size(), (page + 1) * pageSize); at++) {
            Item item = filtered.get(at);
            int y = listTop + (at - page * pageSize) * 25;
            g.fill(left + 10, y, left + panelWidth - 10, y + 24, (at % 2 == 0) ? t.surface() : t.band());
            g.renderItem(new ItemStack(item), left + 14, y + 4);
            Nb.text(g, font, Nb.clip(font, item.getDescription().getString(), panelWidth - 155), left + 37, y + 3, t.text());
            Nb.text(g, font, (policy.protects(item) ? "保护 · " : "可丢弃 · ")
                    + Nb.clip(font, id(item), panelWidth - 206), left + 37, y + 14,
                    policy.protects(item) ? t.ok() : t.textDim());
            if (mx >= left + 10 && mx < left + panelWidth - 112 && my >= y && my < y + 24) hovered = item;
        }
        Nb.text(g, font, Nb.clip(font, message, panelWidth - 24), left + 12, top + panelHeight - 17, t.textDim());
        ui.render(new McDrawSurface(g, font), HostThemeColors.current(), mx, my, net.minecraft.Util.getMillis());
        if (hovered != null && !ui.hasOverlay()) g.renderTooltip(font, new ItemStack(hovered), mx, my);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (ui.mouseClicked(mx, my, button)) { setFocused(null); return true; }
        return super.mouseClicked(mx, my, button);
    }
    @Override public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (ui.mouseScrolled(mx, my, dy)) return true;
        if (my >= listTop && my < top + panelHeight - 48) {
            page = Math.clamp(page + (dy < 0 ? 1 : -1), 0, pages() - 1); rebuild(); return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (ui.hasOverlay() && ui.keyPressed(key, modifiers)) return true;
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { if (!saving) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

package com.kyou.gtnhmelogisticsenhancement.client;

import java.util.UUID;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.kyou.gtnhmelogisticsenhancement.gui.ConfigurationInput;
import com.kyou.gtnhmelogisticsenhancement.gui.ContainerTransferDrive;
import com.kyou.gtnhmelogisticsenhancement.gui.DriveGuiAction;
import com.kyou.gtnhmelogisticsenhancement.gui.DriveGuiNetwork;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** A compact, texture-independent machine panel. Configuration always comes from the server snapshot. */
@SideOnly(Side.CLIENT)
public final class GuiTransferDrive extends GuiContainer {

    private final ContainerTransferDrive container;
    private GuiTextField period;
    private GuiTextField name;
    private GuiTextField priority;
    private GuiTextField storagePriority;
    private GuiTextField fillPercent;
    private int fillGroup;
    private long fillRevision;
    private String selectedId = "";
    private int targetPage;
    private long editorRevision;
    private long periodRevision;
    private long storagePriorityRevision;
    private String localError = "";
    private boolean metadataSubmitted;
    private long clickSequence;

    public GuiTransferDrive(final ContainerTransferDrive container) {
        super(container);
        this.container = container;
        this.xSize = 320;
        this.ySize = 240;
    }

    private static String tr(final String key, final Object... arguments) {
        return StatCollector.translateToLocalFormatted("gtnhmelogisticsenhancement.gui." + key, arguments);
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        for (int group = 0; group < 8; group++) {
            this.button(group, 10 + group * 20, 23, 19, Integer.toString(group + 1));
        }
        this.button(10, 9, 104, 51, tr("unassigned"));
        this.button(11, 62, 104, 51, tr("automatic"));
        this.button(12, 115, 104, 51, tr("specific"));
        this.button(13, 9, 124, 157, "");
        this.button(14, 118, 146, 48, tr("apply"));
        this.button(15, 183, 91, 28, "<");
        this.button(16, 284, 91, 28, ">");
        this.button(17, 257, 167, 55, tr("save"));
        this.button(18, 183, 187, 129, tr("delete"));
        this.button(19, 137, 64, 29, tr("set"));
        this.buttonList.add(new GuiButton(9, this.guiLeft + 142, this.guiTop + 79, 24, 14, tr("set")));
        for (int row = 0; row < 4; row++) {
            this.button(20 + row, 203, 23 + row * 17, 109, "");
            this.button(30 + row, 183, 23 + row * 17, 18, "");
        }
        this.period = this.field(9, 147, 65, ConfigurationInput.MAX_NUMBER_LENGTH);
        this.name = this.field(183, 135, 129, ConfigurationInput.MAX_TEXT_BYTES);
        this.priority = this.field(183, 168, 69, ConfigurationInput.MAX_NUMBER_LENGTH);
        this.storagePriority = this.field(60, 65, 76, ConfigurationInput.MAX_NUMBER_LENGTH);
        this.fillPercent = this.field(108, 80, 31, ConfigurationInput.MAX_NUMBER_LENGTH);
        this.refreshFillEditor();
        this.period.setText(
            Integer.toString(
                this.state()
                    .getInteger("period")));
        this.periodRevision = this.state()
            .getLong("revision");
        this.storagePriority.setText(
            Integer.toString(
                this.state()
                    .getInteger("storagePriority")));
        this.storagePriorityRevision = this.state()
            .getLong("revision");
        this.select(this.selectedId);
    }

    private void button(final int id, final int x, final int y, final int width, final String label) {
        this.buttonList.add(new GuiButton(id, this.guiLeft + x, this.guiTop + y, width, 16, label));
    }

    private GuiTextField field(final int x, final int y, final int width, final int length) {
        final GuiTextField field = new GuiTextField(this.fontRendererObj, this.guiLeft + x, this.guiTop + y, width, 14);
        field.setMaxStringLength(length);
        return field;
    }

    private NBTTagCompound state() {
        return this.container.getClientState();
    }

    private void refreshFillEditor() {
        this.fillGroup = this.state()
            .getInteger("page");
        final String key = "fill" + this.fillGroup;
        this.fillPercent.setText(
            Integer.toString(
                this.state()
                    .hasKey(key)
                        ? this.state()
                            .getInteger(key)
                        : 100));
        this.fillRevision = this.state()
            .getLong("revision");
    }

    private NBTTagList targets() {
        return this.state()
            .getTagList("targets", 10);
    }

    private NBTTagList chosen() {
        return this.state()
            .getTagList(
                "selected" + this.state()
                    .getInteger("page"),
                8);
    }

    private boolean isChosen(final String id) {
        final NBTTagList selected = this.chosen();
        for (int index = 0; index < selected.tagCount(); index++) {
            if (id.equals(selected.getStringTagAt(index))) {
                return true;
            }
        }
        return false;
    }

    private NBTTagCompound selected() {
        final NBTTagList targets = this.targets();
        for (int index = 0; index < targets.tagCount(); index++) {
            final NBTTagCompound target = targets.getCompoundTagAt(index);
            if (target.getString("id")
                .equals(this.selectedId)) {
                return target;
            }
        }
        return null;
    }

    private void select(final String id) {
        this.selectedId = id;
        final NBTTagCompound target = this.selected();
        this.name.setText(target == null ? "" : target.getString("name"));
        this.priority.setText(target == null ? "" : Integer.toString(target.getInteger("priority")));
        this.editorRevision = this.state()
            .getLong("revision");
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.period.updateCursorCounter();
        this.name.updateCursorCounter();
        this.priority.updateCursorCounter();
        this.storagePriority.updateCursorCounter();
        this.fillPercent.updateCursorCounter();
        if (this.fillGroup != this.state()
            .getInteger("page")) {
            this.fillPercent.setFocused(false);
            this.refreshFillEditor();
        } else if (!this.fillPercent.isFocused()) {
            this.refreshFillEditor();
        }
        if (this.metadataSubmitted && this.state()
            .getLong("revision") != this.editorRevision) {
            this.metadataSubmitted = false;
            this.select(this.selectedId);
        }
        if (!this.period.isFocused()) {
            this.period.setText(
                Integer.toString(
                    this.state()
                        .getInteger("period")));
            this.periodRevision = this.state()
                .getLong("revision");
        }
        if (!this.storagePriority.isFocused()) {
            this.storagePriority.setText(
                Integer.toString(
                    this.state()
                        .getInteger("storagePriority")));
            this.storagePriorityRevision = this.state()
                .getLong("revision");
        }
        if (!this.selectedId.isEmpty() && this.selected() == null) {
            this.select("");
        }
        this.targetPage = Math.min(
            this.targetPage,
            Math.max(
                0,
                (this.targets()
                    .tagCount() - 1) / 4));
        final boolean editable = this.state()
            .hasKey("session")
            && !this.state()
                .getBoolean("locked");
        for (final Object element : this.buttonList) {
            final GuiButton button = (GuiButton) element;
            button.enabled = editable;
            if (button.id < 8) {
                button.enabled = this.state()
                    .hasKey("session")
                    && button.id != this.state()
                        .getInteger("page");
            } else if (button.id == 17 || button.id == 18) {
                button.enabled &= this.selected() != null;
            } else if (button.id == 13) {
                button.displayString = tr(
                    "access",
                    this.state()
                        .getString("access"));
            } else if (button.id == 15 || button.id == 16) {
                button.enabled = button.id == 15 ? this.targetPage > 0
                    : (this.targetPage + 1) * 4 < this.targets()
                        .tagCount();
            } else if (button.id >= 20) {
                final boolean checkbox = button.id >= 30;
                final int index = this.targetPage * 4 + button.id - (checkbox ? 30 : 20);
                button.visible = index < this.targets()
                    .tagCount();
                button.enabled = button.visible && (!checkbox || editable);
                if (button.visible) {
                    final NBTTagCompound target = this.targets()
                        .getCompoundTagAt(index);
                    button.displayString = checkbox ? (this.isChosen(target.getString("id")) ? "x" : "-")
                        : this.fontRendererObj.trimStringToWidth(
                            (target.getString("id")
                                .equals(this.selectedId) ? ">" : "") + (index + 1)
                                + ". "
                                + target.getString("name")
                                + " ["
                                + target.getInteger("priority")
                                + "]",
                            101);
                }
            }
        }
    }

    @Override
    protected void actionPerformed(final GuiButton button) {
        this.localError = "";
        final int group = this.state()
            .getInteger("page");
        if (button.id < 8) {
            this.request(
                DriveGuiAction.Kind.PAGE,
                button.id,
                "",
                "",
                this.state()
                    .getLong("revision"));
        } else if (button.id >= 20) {
            final boolean checkbox = button.id >= 30;
            final int index = this.targetPage * 4 + button.id - (checkbox ? 30 : 20);
            if (index < this.targets()
                .tagCount()) {
                final String id = this.targets()
                    .getCompoundTagAt(index)
                    .getString("id");
                if (checkbox) {
                    this.request(
                        DriveGuiAction.Kind.TOGGLE_TARGET,
                        group,
                        "",
                        "",
                        this.state()
                            .getLong("revision"),
                        id);
                } else {
                    this.select(id);
                }
            }
        } else {
            switch (button.id) {
                case 9:
                    if (this.fillGroup != group) {
                        this.fillPercent.setFocused(false);
                        this.refreshFillEditor();
                        break;
                    }
                    this.request(
                        DriveGuiAction.Kind.FILL_PERCENT,
                        this.fillGroup,
                        "",
                        this.fillPercent.getText(),
                        this.fillRevision);
                    this.fillPercent.setFocused(false);
                    break;
                case 10:
                case 11:
                case 12:
                    final DriveGuiAction.Kind kind = button.id == 10 ? DriveGuiAction.Kind.UNASSIGNED
                        : button.id == 11 ? DriveGuiAction.Kind.AUTOMATIC : DriveGuiAction.Kind.SPECIFIC_MODE;
                    this.request(
                        kind,
                        group,
                        "",
                        "",
                        this.state()
                            .getLong("revision"));
                    break;
                case 13:
                    final String current = this.state()
                        .getString("access");
                    final String next = "READ_WRITE".equals(current) ? "READ"
                        : "READ".equals(current) ? "WRITE" : "READ_WRITE";
                    this.request(
                        DriveGuiAction.Kind.ACCESS,
                        group,
                        next,
                        "",
                        this.state()
                            .getLong("revision"));
                    break;
                case 14:
                    this.request(DriveGuiAction.Kind.PERIOD, group, "", this.period.getText(), this.periodRevision);
                    break;
                case 15:
                    this.targetPage--;
                    break;
                case 16:
                    this.targetPage++;
                    break;
                case 17:
                    this.metadataSubmitted = true;
                    this.request(
                        DriveGuiAction.Kind.UPDATE_TARGET,
                        group,
                        this.name.getText(),
                        this.priority.getText(),
                        this.editorRevision);
                    break;
                case 18:
                    this.request(
                        DriveGuiAction.Kind.DELETE_TARGET,
                        group,
                        "",
                        "",
                        this.state()
                            .getLong("revision"));
                    break;
                case 19:
                    this.request(
                        DriveGuiAction.Kind.STORAGE_PRIORITY,
                        group,
                        "",
                        this.storagePriority.getText(),
                        this.storagePriorityRevision);
                    break;
                default:
                    break;
            }
        }
    }

    private void request(final DriveGuiAction.Kind kind, final int group, final String text, final String number,
        final long revision) {
        this.request(kind, group, text, number, revision, this.selectedId);
    }

    private void request(final DriveGuiAction.Kind kind, final int group, final String text, final String number,
        final long revision, final String targetId) {
        if (!this.state()
            .hasKey("session")) {
            return;
        }
        try {
            final UUID id = targetId.isEmpty() ? null : UUID.fromString(targetId);
            final String normalizedText = kind == DriveGuiAction.Kind.UPDATE_TARGET ? ConfigurationInput.alias(text)
                : text;
            final String normalizedNumber = kind
                == DriveGuiAction.Kind.FILL_PERCENT
                    ? Integer.toString(ConfigurationInput.clampInteger(number, 0, 100))
                    : kind
                        == DriveGuiAction.Kind.PERIOD
                            ? Integer.toString(ConfigurationInput.clampInteger(number, 1, 86400))
                            : kind == DriveGuiAction.Kind.UPDATE_TARGET || kind == DriveGuiAction.Kind.STORAGE_PRIORITY
                                ? Integer.toString(
                                    ConfigurationInput.clampInteger(number, Integer.MIN_VALUE, Integer.MAX_VALUE))
                                : number;
            DriveGuiNetwork.request(
                this.container.windowId,
                new DriveGuiAction(
                    kind,
                    UUID.fromString(
                        this.state()
                            .getString("session")),
                    revision,
                    group,
                    id,
                    normalizedText,
                    normalizedNumber));
        } catch (IllegalArgumentException failure) {
            this.localError = tr("invalid");
        }
    }

    @Override
    protected void handleMouseClick(final Slot slot, final int slotId, final int button, final int mode) {
        if (!this.state()
            .hasKey("session")) {
            return;
        }
        final DriveGuiNetwork.ClickMessage message = new DriveGuiNetwork.ClickMessage();
        message.window = this.container.windowId;
        message.session = UUID.fromString(
            this.state()
                .getString("session"));
        message.pageRevision = this.state()
            .getLong("pageRevision");
        message.configurationRevision = this.state()
            .getLong("revision");
        message.sequence = ++this.clickSequence;
        message.slot = slotId;
        message.button = button;
        message.mode = mode;
        DriveGuiNetwork.click(message);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(final float partialTicks, final int mouseX, final int mouseY) {
        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + this.ySize, 0xFFB9BCC0);
        drawRect(this.guiLeft + 176, this.guiTop + 4, this.guiLeft + 177, this.guiTop + 237, 0xFF6A717A);
        for (final Object element : this.container.inventorySlots) {
            final net.minecraft.inventory.Slot slot = (net.minecraft.inventory.Slot) element;
            if (slot.xDisplayPosition >= 0) {
                final int x = this.guiLeft + slot.xDisplayPosition;
                final int y = this.guiTop + slot.yDisplayPosition;
                drawRect(x - 1, y - 1, x + 17, y + 17, 0xFF565C63);
                drawRect(x, y, x + 16, y + 16, 0xFF91969C);
            }
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(final int mouseX, final int mouseY) {
        this.line(tr("title"), 9, 7, 162);
        this.line(
            tr(
                "targets",
                this.targets()
                    .tagCount()),
            183,
            7,
            129);
        this.line(tr("storage_priority_short"), 9, 68, 47);
        final int group = this.state()
            .getInteger("page");
        this.line(
            tr(
                "route",
                group + 1,
                tr(
                    this.state()
                        .getString("route" + group)
                        .toLowerCase(java.util.Locale.ROOT))),
            9,
            81,
            62);
        this.line(tr("fill_short"), 74, 83, 32);
        final int chosen = this.chosen()
            .tagCount();
        final String mode = this.state()
            .getString("route" + group);
        this.line(
            "AUTOMATIC".equals(mode) ? tr(
                "automatic_targets",
                this.targets()
                    .tagCount())
                : "SPECIFIC".equals(mode) ? tr(chosen == 0 ? "no_selected_targets" : "selected_count", chosen)
                    : tr("transfer_disabled"),
            9,
            93,
            162);
        this.line(tr("seconds"), 79, 151, 36);
        this.line(
            (this.targetPage + 1) + " / "
                + Math.max(
                    1,
                    (this.targets()
                        .tagCount() + 3) / 4),
            224,
            95,
            56);
        final NBTTagCompound selected = this.selected();
        if (selected != null) {
            this.line(
                "D" + selected.getInteger("dimension")
                    + " "
                    + selected.getInteger("x")
                    + ", "
                    + selected.getInteger("y")
                    + ", "
                    + selected.getInteger("z"),
                183,
                113,
                129);
        }
        this.line(tr("name"), 183, 125, 129);
        this.line(tr("priority"), 183, 157, 129);
        this.line(
            tr(
                "source",
                tr(
                    this.state()
                        .getBoolean("connected") ? "active" : "offline")),
            183,
            207,
            129);
        this.line(
            tr(
                this.state()
                    .getBoolean("recovery") ? "recovery"
                        : this.state()
                            .getBoolean("locked") ? "locked"
                                : this.state()
                                    .getBoolean("connected")
                                    && this.state()
                                        .getBoolean("configured") ? "ready" : "not_ready"),
            183,
            218,
            129);
        this.line(
            this.localError.isEmpty() ? tr(
                this.state()
                    .getString("feedback"))
                : this.localError,
            183,
            229,
            129);
    }

    private void line(final String text, final int x, final int y, final int width) {
        this.fontRendererObj.drawString(this.fontRendererObj.trimStringToWidth(text, width), x, y, 0x202830);
    }

    @Override
    public void drawScreen(final int mouseX, final int mouseY, final float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.period.drawTextBox();
        this.name.drawTextBox();
        this.priority.drawTextBox();
        this.storagePriority.drawTextBox();
        this.fillPercent.drawTextBox();
        if (mouseX >= this.guiLeft + 74 && mouseX < this.guiLeft + 167
            && mouseY >= this.guiTop + 79
            && mouseY < this.guiTop + 94) {
            final java.util.List<String> lines = new java.util.ArrayList<>();
            for (final String key : new String[] { "fill_percent", "fill_hint", "fill_bound_hint" }) {
                lines.addAll(this.fontRendererObj.listFormattedStringToWidth(tr(key), 240));
            }
            this.drawHoveringText(lines, mouseX, mouseY, this.fontRendererObj);
        }
        if (mouseX >= this.guiLeft + 9 && mouseX < this.guiLeft + 57
            && mouseY >= this.guiTop + 64
            && mouseY < this.guiTop + 81) {
            final java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add(tr("storage_priority"));
            lines.add(tr("storage_priority_hint"));
            lines.add(tr("transfer_types"));
            this.drawHoveringText(lines, mouseX, mouseY, this.fontRendererObj);
        }
        if (mouseX >= this.guiLeft + 9 && mouseX < this.guiLeft + 172
            && mouseY >= this.guiTop + 81
            && mouseY < this.guiTop + 104
            && (mouseY >= this.guiTop + 94 || mouseX < this.guiLeft + 74)) {
            final java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add(
                tr(
                    "route",
                    this.state()
                        .getInteger("page") + 1,
                    tr(
                        this.state()
                            .getString(
                                "route" + this.state()
                                    .getInteger("page"))
                            .toLowerCase(java.util.Locale.ROOT))));
            lines.add(
                tr(
                    "selected_count",
                    this.chosen()
                        .tagCount()));
            lines.add(tr("select_hint"));
            for (int index = 0; index < this.targets()
                .tagCount(); index++) {
                final NBTTagCompound target = this.targets()
                    .getCompoundTagAt(index);
                if (this.isChosen(target.getString("id"))) {
                    lines.add(target.getString("name") + " [" + target.getInteger("priority") + "]");
                }
            }
            // The full selection remains visible through the four-row target pages.
            if (lines.size() > 10) {
                lines.subList(10, lines.size())
                    .clear();
                lines.add(tr("selection_pages"));
            }
            this.drawHoveringText(lines, mouseX, mouseY, this.fontRendererObj);
        }
        if (mouseX >= this.guiLeft + 180 && mouseY >= this.guiTop + 23
            && mouseX < this.guiLeft + this.xSize
            && mouseY < this.guiTop + this.ySize) {
            final java.util.List<String> lines = new java.util.ArrayList<>();
            NBTTagCompound detail = this.selected();
            if (mouseY < this.guiTop + 91) {
                final int index = this.targetPage * 4 + (mouseY - this.guiTop - 23) / 17;
                detail = index < this.targets()
                    .tagCount() ? this.targets()
                        .getCompoundTagAt(index) : null;
            }
            if (detail != null) {
                lines.add(detail.getString("name"));
                lines.add(
                    "D" + detail.getInteger("dimension")
                        + ": "
                        + detail.getInteger("x")
                        + ", "
                        + detail.getInteger("y")
                        + ", "
                        + detail.getInteger("z"));
                lines.add(tr("priority") + ": " + detail.getInteger("priority"));
                lines.add(tr("last_check", tr("target_status." + detail.getString("status"))));
            }
            lines.add(tr("status", this.schedulerStatus()));
            lines.add(
                this.localError.isEmpty() ? tr(
                    this.state()
                        .getString("feedback"))
                    : this.localError);
            lines.add(tr("not_proof"));
            if (mouseY < this.guiTop + 91 || mouseY >= this.guiTop + 204
                || mouseY >= this.guiTop + 111 && mouseY < this.guiTop + 123) {
                this.drawHoveringText(lines, mouseX, mouseY, this.fontRendererObj);
            }
        }
    }

    private String schedulerStatus() {
        final String status = this.state()
            .getString("status");
        final String key = "gtnhmelogisticsenhancement.gui.scheduler." + status;
        return StatCollector.canTranslate(key) ? StatCollector.translateToLocal(key) : status;
    }

    @Override
    protected void mouseClicked(final int mouseX, final int mouseY, final int button) {
        // Keep text edits intact while their Apply/Save button processes this click.
        super.mouseClicked(mouseX, mouseY, button);
        this.period.mouseClicked(mouseX, mouseY, button);
        this.name.mouseClicked(mouseX, mouseY, button);
        this.priority.mouseClicked(mouseX, mouseY, button);
        this.storagePriority.mouseClicked(mouseX, mouseY, button);
        this.fillPercent.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(final char character, final int key) {
        if (key != Keyboard.KEY_ESCAPE
            && (this.period.textboxKeyTyped(character, key) || this.name.textboxKeyTyped(character, key)
                || this.priority.textboxKeyTyped(character, key)
                || this.storagePriority.textboxKeyTyped(character, key)
                || this.fillPercent.textboxKeyTyped(character, key))) {
            return;
        }
        super.keyTyped(character, key);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }
}

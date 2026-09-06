package ml.mypals.microtimingreplay.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import ml.mypals.microtimingreplay.MicroTimingReplay;
import ml.mypals.microtimingreplay.client.ClientReplayState;
import ml.mypals.microtimingreplay.client.MTRClientConfig;
import ml.mypals.microtimingreplay.client.MTRClientNetworking;
import ml.mypals.microtimingreplay.client.TimelineAutoHide;
import ml.mypals.microtimingreplay.client.camera.ViewportCamera;
import ml.mypals.microtimingreplay.client.iconcomponents.IconButton;
import ml.mypals.microtimingreplay.network.MTRPayloads;
import ml.mypals.microtimingreplay.util.MTRComponent;
import ml.mypals.microtimingreplay.util.MTRHelpText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;

import java.util.*;

import static ml.mypals.microtimingreplay.replay.dialog.StackTraceScreenGenerator.formatStackTraceLine;


@Environment(EnvType.CLIENT)
public class TimelineScreen extends Screen {

    private static final int ROW_HEIGHT = 11;
    private static final int LIST_TOP = 44;
    private static final int LIST_BOTTOM_MARGIN = 30;
    private static final int INDENT = 9;
    private static final int WIDTH_AT_SCALE_1 = 400;
    private static final int WIDTH_PER_SCALE = 100;
    private static final int MIN_PANEL_WIDTH = 100;
    /** The gap between the two columns is reserved space, so never let them close it. */
    private static final int MIN_MIDDLE_GAP = 150;

    private static final int LIST_X = 0;
    private static final int EDGE_PAD = 0;
    private static final int V_SCROLLBAR = 5;
    private static final int H_SCROLLBAR = 4;
    private static final int JUMP_ZONE = 20;
    private static final int H_SCROLL_STEP = 16;

    private static final double RETARGET_RANGE = 192.0;
    private static final long DOUBLE_CLICK_MS = 250;
    private static final double DOUBLE_CLICK_SLOP = 4.0;

    private static final int SEARCH_MATCH_BG = 0x40FFD24A;
    private static final int SEARCH_CURRENT_BG = 0x80FFB000;
    private static final int SEARCH_HEIGHT = 14;
    private static final int SEARCH_WIDTH = 150;

    private static final int TOOLTIP_TRACE_LINES = 20;
    private static final double SUMMARY_MAX_SHARE = 0.5;
    private static final int FONT_MIN_PERCENT = 5;
    private static final int FONT_MAX_PERCENT = 200;

    private final Set<Integer> collapsed = new HashSet<>();
    private List<Integer> visible = new ArrayList<>();
    /** Number of rendered timeline rows, including the extra Tick separator rows. */
    private int timelineContentSlots = 0;

    /** Shared by the list and the detail column, so the two read as a matched pair. */
    private float fontScale = 1.0f;

    private int panelWidth = WIDTH_AT_SCALE_1;
    private int panelWidthOverride = -1;
    private float leftFontScale = 1.0F;
    private float rightFontScale = 1.0F;

    private int scroll = 0;
    private int hScroll = 0;
    /** Widest row drawn last frame; what {@link #hScroll} is clamped against. */
    private int measuredContentWidth = 0;

    private int selectedStep = -1;
    private int lastRevision = -1;
    private int lastCursorRow = -1;

    private int detailScroll = 0;
    private int detailHScroll = 0;
    private int summaryScroll = 0;

    private enum Drag { NONE, TIMELINE_V, TIMELINE_H, DETAIL_V, DETAIL_H, SUMMARY_V,
        CAMERA_ORBIT, CAMERA_PAN, GRAB_TIMELINE, GRAB_DETAIL, GRAB_SUMMARY }

    private Drag dragging = Drag.NONE;

    /**
     * Left-over pixels of a right-drag. Vertical scrolling counts rows, so without carrying the
     * remainder a slow drag would round to zero every frame and never move.
     */
    private double grabRemainder = 0;

    private final ViewportCamera camera = new ViewportCamera();
    private Vec3 lastServerFocus = null;

    private long lastViewportClickMillis = 0;
    private double lastViewportClickX = 0;
    private double lastViewportClickY = 0;

    /** The manual overlay, drawn over everything and dismissed by any key or click. */
    private boolean helpOpen = false;
    private boolean settingsOpen = false;
    private boolean settingsInputMode = false;

    private final List<AbstractWidget> mainWidgets = new ArrayList<>();
    private final List<AbstractWidget> settingsWidgets = new ArrayList<>();
    private SidebarWidthSlider sidebarWidthSlider;
    private EditBox sidebarWidthInput;
    private LeftFontSlider leftFontSlider;
    private EditBox leftFontInput;
    private RightFontSlider rightFontSlider;
    private EditBox rightFontInput;
    private Button settingsModeButton;
    private Button settingsDoneButton;
    private int settingsBoxX;
    private int settingsBoxY;
    private int settingsBoxWidth;
    private int settingsBoxHeight;

    private EditBox searchBox;
    private List<Integer> matches = List.of();
    private Set<Integer> matchSet = Set.of();
    private int matchCursor = -1;
    private List<String> searchIndex;
    private int searchIndexRevision = -1;

    private final WidgetTooltipHolder stackTooltip = new WidgetTooltipHolder();
    /** Hit box of the call-stack header, stamped while drawing and read on click. */
    private ScreenRectangle stackHeaderRect = ScreenRectangle.empty();
    private long copiedAtMillis = 0;

    private Button followButton;

    public TimelineScreen() {
        super(MTRComponent.translatable("mtr.timeline.title", "MTR Timeline"));
        panelWidthOverride = MTRClientConfig.timelinePanelWidth();
        leftFontScale = MTRClientConfig.timelineLeftFontPercent() / 100.0F;
        rightFontScale = MTRClientConfig.timelineRightFontPercent() / 100.0F;
    }

    private List<MTRPayloads.TimelineRow> rows() {
        return ClientReplayState.rows();
    }

    /**
     * Recomputed in {@link #init()}, which vanilla also calls on resize — so changing the
     * GUI scale in the options screen and coming back lands on the right width.
     */
    private int computePanelWidth() {
        int scale = Math.max(1, this.minecraft.getWindow().getGuiScale());
        int preferred = Math.max(MIN_PANEL_WIDTH, WIDTH_AT_SCALE_1 - (scale - 1) * WIDTH_PER_SCALE);

        // A small window at scale 1 can be narrower than two preferred columns.
        int fits = Math.max(MIN_PANEL_WIDTH, (this.width - LIST_X - EDGE_PAD - MIN_MIDDLE_GAP) / 2);
        int autoWidth = Math.max(1, Math.min(preferred, fits));
        if (panelWidthOverride > 0) {
            return Math.clamp(panelWidthOverride, 1, fits);
        }

        return autoWidth;
    }

    public void setPanelWidthOverride(int width) {
        int fits = Math.max(
                MIN_PANEL_WIDTH,
                (this.width - LIST_X - EDGE_PAD - MIN_MIDDLE_GAP) / 2
        );
        panelWidthOverride = Math.clamp(width, MIN_PANEL_WIDTH, fits);
        MTRClientConfig.setTimelinePanelWidth(panelWidthOverride);
        panelWidth = computePanelWidth();

        cachedView = null;
        cachedStep = -1;
        cachedPanelWidth = -1;
        cachedRightFontScale = -1.0F;
        measuredContentWidth = 0;

        clampScroll();
        clampDetailScroll();
        clampSummaryScroll();
    }

    @Override
    protected void init() {
        // Minecraft rebuilds a screen's widgets when returning from another screen
        // (for example FilterScreen). Clear our tracking before recreating them, or
        // settings widgets remain in the old Screen list and cannot be rendered/input.
        mainWidgets.clear();
        settingsWidgets.clear();
        sidebarWidthSlider = null;
        sidebarWidthInput = null;
        leftFontSlider = null;
        leftFontInput = null;
        rightFontSlider = null;
        rightFontInput = null;
        settingsModeButton = null;
        settingsDoneButton = null;

        panelWidth = computePanelWidth();
        rebuildVisible();
        lastRevision = ClientReplayState.timelineRevision();

        // Land on whatever the replay is sitting on rather than an empty detail column.
        lastCursorRow = ClientReplayState.cursorRow();
        selectStep(ClientReplayState.currentStep());

        int x = 8;
        int y = 22;
        x += addToolButton(MTRComponent.translatable("mtr.timeline.expand_all", "Expand all"), x, y, 74,
                () -> { collapsed.clear(); rebuildVisible(); });
        x += addToolButton(MTRComponent.translatable("mtr.timeline.collapse_all", "Collapse all"), x, y, 84,
                () -> collapseToLevel(0));
        for (int level = 1; level <= 3; level++) {
            int target = level;
            x += addToolButton(Component.literal("L" + level), x, y, 26, () -> collapseToLevel(target));
        }

        followButton = addMainWidget(Button.builder(followLabel(), button -> {
            MTRClientConfig.setFollowCursor(!MTRClientConfig.followCursor());
            button.setMessage(followLabel());
        }).bounds(x, y, 92, 16).build());

        Button cameraFollowButton = addMainWidget(Button.builder(cameraFollow(), button -> {
            MTRClientNetworking.setCameraFollow(!ClientReplayState.cameraFollow());
            button.setMessage(cameraFollow());
        }).bounds(followButton.getWidth() + x + 4, y, 92, 16).build());

        Button markersButton = addMainWidget(Button.builder(markerLabel(), button -> {
            MTRClientConfig.setHideMarkers(!MTRClientConfig.hideMarkers());
            button.setMessage(markerLabel());
        }).bounds(cameraFollowButton.getX() + cameraFollowButton.getWidth() + 4, y, 92, 16).build());

        Button helpButton = addMainWidget(Button.builder(
            MTRComponent.translatable("mtr.timeline.help", "Manual"),
            button -> toggleHelp())
            .bounds(markersButton.getX() + markersButton.getWidth() + 4, y, 60, 16)
            .build());

        Button settingsButton = addMainWidget(new IconButton(helpButton.getX() + helpButton.getWidth() + 4
                , y, 16, 16,
                MTRComponent.translatable("mtr.timeline.setting", "Setting"),
                button -> toggleSettings(),
                MicroTimingReplay.id("textures/gui/settings.png"),
                2, 2, ARGB.white(100)));

        initSettingsWidgets();

        int bottom = this.height - 24;
        addMainWidget(Button.builder(MTRComponent.translatable("mtr.timeline.backward", "◀ Back"),
                b -> MTRClientNetworking.step(false)).bounds(8, bottom, 70, 18).build());
        addMainWidget(Button.builder(MTRComponent.translatable("mtr.timeline.forward", "Forward ▶"),
                b -> MTRClientNetworking.step(true)).bounds(82, bottom, 70, 18).build());
        addMainWidget(Button.builder(MTRComponent.translatable("mtr.timeline.filter", "Filter"),
                b -> this.minecraft.gui.setScreen(new FilterScreen(this))).bounds(this.width - 154, bottom, 70, 18).build());
        addMainWidget(Button.builder(MTRComponent.translatable("mtr.timeline.close", "Close"),
                b -> onClose()).bounds(this.width - 80, bottom, 70, 18).build());

        updateSettingsWidgetVisibility();

        if (MTRClientConfig.followCursor()) {
            scrollTo(ClientReplayState.cursorRow());
        }

        if (searchBox != null) {
            String query = searchBox.getValue();
            searchBox = null;
            openSearch();
            searchBox.setValue(query);
        }
    }

    private int addToolButton(Component label, int x, int y, int width, Runnable action) {
        addMainWidget(Button.builder(label, b -> action.run()).bounds(x, y, width, 16).build());
        return width + 4;
    }

    private <T extends AbstractWidget> T addMainWidget(T widget) {
        mainWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private <T extends AbstractWidget> T addSettingsWidget(T widget) {
        settingsWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void initSettingsWidgets() {
        if (sidebarWidthSlider != null) return;

        sidebarWidthSlider = addSettingsWidget(new SidebarWidthSlider(
                0, 0, 240, 20, sidebarWidthLabel(), settingsCurrentWidth()));
        sidebarWidthInput = addSettingsWidget(new EditBox(
                this.font, 0, 0, 240, 20,
                MTRComponent.translatable("mtr.timeline.settings.sidebar_width", "侧边栏宽度")));
        sidebarWidthInput.setMaxLength(4);
        sidebarWidthInput.setHint(MTRComponent.translatable(
                "mtr.timeline.settings.sidebar_width_hint", "100 - %d", settingsMaxWidth()));
        sidebarWidthInput.setResponder(this::onSidebarWidthInputChanged);

        leftFontSlider = addSettingsWidget(new LeftFontSlider(
                0, 0, 240, 20, leftFontLabel(), leftFontPercent()));
        leftFontInput = addSettingsWidget(new EditBox(
                this.font, 0, 0, 240, 20,
                MTRComponent.translatable("mtr.timeline.settings.left_font_size", "左侧字体大小")));
        leftFontInput.setMaxLength(3);
        leftFontInput.setHint(MTRComponent.translatable(
                "mtr.timeline.settings.left_font_size_hint", "输入 %d - %d%%",
                FONT_MIN_PERCENT, FONT_MAX_PERCENT));
        leftFontInput.setResponder(this::onLeftFontInputChanged);

        rightFontSlider = addSettingsWidget(new RightFontSlider(
                0, 0, 240, 20, rightFontLabel(), rightFontPercent()));
        rightFontInput = addSettingsWidget(new EditBox(
                this.font, 0, 0, 240, 20,
                MTRComponent.translatable("mtr.timeline.settings.right_font_size", "右侧字体大小")));
        rightFontInput.setMaxLength(3);
        rightFontInput.setHint(MTRComponent.translatable(
                "mtr.timeline.settings.right_font_size_hint", "输入 %d - %d%%",
                FONT_MIN_PERCENT, FONT_MAX_PERCENT));
        rightFontInput.setResponder(this::onRightFontInputChanged);

        settingsModeButton = addSettingsWidget(Button.builder(settingsModeLabel(), button -> {
            settingsInputMode = !settingsInputMode;
            updateSettingsWidgetVisibility();
            if (settingsInputMode) {
                sidebarWidthInput.setValue(Integer.toString(settingsCurrentWidth()));
                leftFontInput.setValue(Integer.toString(leftFontPercent()));
                rightFontInput.setValue(Integer.toString(rightFontPercent()));
                setFocused(sidebarWidthInput);
                sidebarWidthInput.setFocused(true);
            } else {
                setFocused(sidebarWidthSlider);
                sidebarWidthSlider.setFocused(true);
            }
        }).bounds(0, 0, 112, 20).build());

        settingsDoneButton = addSettingsWidget(Button.builder(
                MTRComponent.translatable("mtr.timeline.settings.done", "完成"),
                button -> setSettingsOpen(false))
                .bounds(0, 0, 80, 20)
                .build());

        updateSettingsWidgetVisibility();
    }

    private void toggleHelp() {
        helpOpen = !helpOpen;
        if (helpOpen) setSettingsOpen(false);
    }

    private void toggleSettings() {
        setSettingsOpen(!settingsOpen);
    }

    private void setSettingsOpen(boolean open) {
        settingsOpen = open;
        if (open) {
            helpOpen = false;
            closeSearch();
            setFocused(null);
            if (sidebarWidthInput != null) {
                sidebarWidthInput.setValue(Integer.toString(settingsCurrentWidth()));
            }
            if (sidebarWidthSlider != null) {
                sidebarWidthSlider.setWidthValueSilently(settingsCurrentWidth());
            }
            if (leftFontInput != null) {
                leftFontInput.setValue(Integer.toString(leftFontPercent()));
            }
            if (leftFontSlider != null) {
                leftFontSlider.setPercentValueSilently(leftFontPercent());
            }
            if (rightFontInput != null) {
                rightFontInput.setValue(Integer.toString(rightFontPercent()));
            }
            if (rightFontSlider != null) {
                rightFontSlider.setPercentValueSilently(rightFontPercent());
            }
        } else {
            setFocused(null);
        }
        updateSettingsWidgetVisibility();
    }

    private void updateSettingsWidgetVisibility() {
        for (AbstractWidget widget : mainWidgets) {
            widget.visible = !settingsOpen;
        }
        for (AbstractWidget widget : settingsWidgets) {
            widget.visible = settingsOpen;
        }
        if (sidebarWidthSlider != null) sidebarWidthSlider.visible = settingsOpen && !settingsInputMode;
        if (sidebarWidthInput != null) sidebarWidthInput.visible = settingsOpen && settingsInputMode;
        if (leftFontSlider != null) leftFontSlider.visible = settingsOpen && !settingsInputMode;
        if (leftFontInput != null) leftFontInput.visible = settingsOpen && settingsInputMode;
        if (rightFontSlider != null) rightFontSlider.visible = settingsOpen && !settingsInputMode;
        if (rightFontInput != null) rightFontInput.visible = settingsOpen && settingsInputMode;
    }

    private int settingsMaxWidth() {
        return Math.max(MIN_PANEL_WIDTH,
                (this.width - LIST_X - EDGE_PAD - MIN_MIDDLE_GAP) / 2);
    }

    private int settingsCurrentWidth() {
        return Math.clamp(panelWidth, MIN_PANEL_WIDTH, settingsMaxWidth());
    }

    private Component sidebarWidthLabel() {
        return MTRComponent.translatable(
                "mtr.timeline.settings.sidebar_width_value", "侧边栏宽度: %d", settingsCurrentWidth());
    }

    private int leftFontPercent() {
        return Math.round(leftFontScale * 100.0F);
    }

    private Component leftFontLabel() {
        return MTRComponent.translatable(
                "mtr.timeline.settings.left_font_size_value", "左侧字体大小: %d%%", leftFontPercent());
    }

    private int rightFontPercent() {
        return Math.round(rightFontScale * 100.0F);
    }

    private Component rightFontLabel() {
        return MTRComponent.translatable(
                "mtr.timeline.settings.right_font_size_value", "右侧字体大小: %d%%", rightFontPercent());
    }

    private int leftTextWidth(Component text) {
        return Math.round(this.font.width(text) * leftFontScale);
    }

    private int leftRowHeight() {
        return MTRWidgets.scaledLineHeight(this.font, leftFontScale);
    }

    private int leftFoldGap() {
        return Math.max(1, Math.round(4.0F * leftFontScale));
    }

    /** Width reserved between a row's indentation origin and its label. */
    private int leftFoldSlotWidth() {
        int symbolWidth = Math.max(
                leftTextWidth(Component.literal("[-]")),
                leftTextWidth(Component.literal("[+]"))
        );
        return Math.max(
                Math.max(2, symbolWidth + leftFoldGap()),
                Math.round(20.0F * leftFontScale)
        );
    }

    private Component foldSymbol(int rowIndex) {
        return Component.literal(collapsed.contains(rowIndex) ? "[+]" : "[-]");
    }

    private int rightTextWidth(FormattedCharSequence text) {
        return Math.round(this.font.width(text) * rightFontScale);
    }

    private int rightRowHeight() {
        return MTRWidgets.scaledLineHeight(this.font, rightFontScale);
    }

    private Component settingsModeLabel() {
        return MTRComponent.translatable(
                settingsInputMode
                        ? "mtr.timeline.settings.use_slider"
                        : "mtr.timeline.settings.use_input",
                settingsInputMode ? "切换为滑块" : "切换为输入框");
    }

    private void onSidebarWidthInputChanged(String text) {
        if (text.isBlank()) return;
        try {
            int width = Integer.parseInt(text);
            if (width >= MIN_PANEL_WIDTH && width <= settingsMaxWidth()) {
                setSidebarWidthFromInput(width);
            }
        } catch (NumberFormatException ignored) {
            // Keep the text field editable while the user is entering a number.
        }
    }

    private void setSidebarWidthFromInput(int width) {
        setPanelWidthOverride(width);
        if (sidebarWidthSlider != null) sidebarWidthSlider.setWidthValue(panelWidth);
    }

    private void onLeftFontInputChanged(String text) {
        if (text.isBlank()) return;
        try {
            int percent = Integer.parseInt(text);
            if (percent >= FONT_MIN_PERCENT && percent <= FONT_MAX_PERCENT) {
                setLeftFontScale(percent);
                if (leftFontSlider != null) leftFontSlider.setPercentValue(percent);
            }
        } catch (NumberFormatException ignored) {
            // Keep the text field editable while the user is entering a number.
        }
    }

    private void setLeftFontScale(int percent) {
        int normalized = Math.clamp(percent, FONT_MIN_PERCENT, FONT_MAX_PERCENT);
        leftFontScale = normalized / 100.0F;
        MTRClientConfig.setTimelineLeftFontPercent(normalized);
        clampScroll();
    }

    private void onRightFontInputChanged(String text) {
        if (text.isBlank()) return;
        try {
            int percent = Integer.parseInt(text);
            if (percent >= FONT_MIN_PERCENT && percent <= FONT_MAX_PERCENT) {
                setRightFontScale(percent);
                if (rightFontSlider != null) rightFontSlider.setPercentValue(percent);
            }
        } catch (NumberFormatException ignored) {
            // Keep the text field editable while the user is entering a number.
        }
    }

    private void setRightFontScale(int percent) {
        int normalized = Math.clamp(percent, FONT_MIN_PERCENT, FONT_MAX_PERCENT);
        rightFontScale = normalized / 100.0F;
        MTRClientConfig.setTimelineRightFontPercent(normalized);
        cachedView = null;
        cachedStep = -1;
        cachedPanelWidth = -1;
        cachedRightFontScale = -1.0F;
        clampDetailScroll();
        clampSummaryScroll();
    }

    private final class SidebarWidthSlider extends AbstractSliderButton {
        private SidebarWidthSlider(int x, int y, int width, int height, Component message, int initialWidth) {
            super(x, y, width, height, message, 0.0);
            this.value = normalizedWidth(initialWidth);
            updateMessage();
        }

        private double normalizedWidth(int width) {
            int max = settingsMaxWidth();
            return max <= MIN_PANEL_WIDTH
                    ? 0.0
                    : (double) (Math.clamp(width, MIN_PANEL_WIDTH, max) - MIN_PANEL_WIDTH)
                    / (double) (max - MIN_PANEL_WIDTH);
        }

        private int widthValue() {
            int max = settingsMaxWidth();
            return MIN_PANEL_WIDTH + (int) Math.round(value * Math.max(0, max - MIN_PANEL_WIDTH));
        }

        private void setWidthValue(int width) {
            setValue(normalizedWidth(width));
        }

        private void setWidthValueSilently(int width) {
            this.value = normalizedWidth(width);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(sidebarWidthLabel());
        }

        @Override
        protected void applyValue() {
            setPanelWidthOverride(widthValue());
            if (sidebarWidthInput != null && !sidebarWidthInput.getValue().equals(Integer.toString(panelWidth))) {
                sidebarWidthInput.setValue(Integer.toString(panelWidth));
            }
        }
    }

    private final class LeftFontSlider extends AbstractSliderButton {
        private LeftFontSlider(int x, int y, int width, int height, Component message, int initialPercent) {
            super(x, y, width, height, message, 0.0);
            this.value = normalizedPercent(initialPercent);
            updateMessage();
        }

        private double normalizedPercent(int percent) {
            return (double) (Math.clamp(percent, FONT_MIN_PERCENT, FONT_MAX_PERCENT)
                    - FONT_MIN_PERCENT)
                    / (double) (FONT_MAX_PERCENT - FONT_MIN_PERCENT);
        }

        private int percentValue() {
            return FONT_MIN_PERCENT
                    + (int) Math.round(value * (FONT_MAX_PERCENT - FONT_MIN_PERCENT));
        }

        private void setPercentValue(int percent) {
            setValue(normalizedPercent(percent));
        }

        private void setPercentValueSilently(int percent) {
            this.value = normalizedPercent(percent);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(leftFontLabel());
        }

        @Override
        protected void applyValue() {
            setLeftFontScale(percentValue());
            if (leftFontInput != null && !leftFontInput.getValue().equals(Integer.toString(percentValue()))) {
                leftFontInput.setValue(Integer.toString(percentValue()));
            }
        }
    }

    private final class RightFontSlider extends AbstractSliderButton {
        private RightFontSlider(int x, int y, int width, int height, Component message, int initialPercent) {
            super(x, y, width, height, message, 0.0);
            this.value = normalizedPercent(initialPercent);
            updateMessage();
        }

        private double normalizedPercent(int percent) {
            return (double) (Math.clamp(percent, FONT_MIN_PERCENT, FONT_MAX_PERCENT) - FONT_MIN_PERCENT)
                    / (double) (FONT_MAX_PERCENT - FONT_MIN_PERCENT);
        }

        private int percentValue() {
            return FONT_MIN_PERCENT + (int) Math.round(value * (FONT_MAX_PERCENT - FONT_MIN_PERCENT));
        }

        private void setPercentValue(int percent) {
            setValue(normalizedPercent(percent));
        }

        private void setPercentValueSilently(int percent) {
            this.value = normalizedPercent(percent);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(rightFontLabel());
        }

        @Override
        protected void applyValue() {
            setRightFontScale(percentValue());
            if (rightFontInput != null && !rightFontInput.getValue().equals(Integer.toString(percentValue()))) {
                rightFontInput.setValue(Integer.toString(percentValue()));
            }
        }
    }

    private Component followLabel() {
        return MTRComponent.translatable(
                MTRClientConfig.followCursor() ? "mtr.timeline.follow_on" : "mtr.timeline.follow_off",
                MTRClientConfig.followCursor() ? "Follow: on" : "Follow: off");
    }

    private Component markerLabel() {
        return MTRComponent.translatable(
                MTRClientConfig.hideMarkers() ? "mtr.timeline.markers_off" : "mtr.timeline.markers_on",
                MTRClientConfig.hideMarkers() ? "Markers: off" : "Markers: on");
    }

    private Component cameraFollow() {
        return MTRComponent.translatable(
                ClientReplayState.cameraFollow() ? "mtr.timeline.camera_follow_on" : "mtr.timeline.camera_follow_off",
                ClientReplayState.cameraFollow() ? "CameraFollow: on" : "CameraFollow: off");
    }

    private int listRight() {
        return LIST_X + panelWidth;
    }

    /** Right edge of row content, i.e. inside the vertical scrollbar. */
    private int contentRight() {
        return listRight() - V_SCROLLBAR - 2;
    }

    /** Right edge of the sideways-scrolling label region, before the pinned jump box. */
    private int labelRight() {
        return contentRight() - JUMP_ZONE;
    }

    private int labelLeft() {
        return LIST_X + 6;
    }

    private int detailX() {
        return this.width - panelWidth - EDGE_PAD;
    }

    private int listBottom() {
        return this.height - LIST_BOTTOM_MARGIN;
    }

    /**
     * Where rows stop. The horizontal bar's strip is reserved unconditionally rather than
     * only when scrolling is possible, so the last row does not jump as content width changes.
     */
    private int rowsBottom() {
        return listBottom() - H_SCROLLBAR - 2;
    }

    private int hScrollbarY() {
        return rowsBottom() + 1;
    }

    private int hTrackLeft() {
        return LIST_X + 1;
    }

    private int hTrackWidth() {
        return labelRight() - hTrackLeft();
    }

    // The detail column mirrors the list chrome, but keeps an independently scaled font metric.

    private int detailContentRight() {
        return detailX() + panelWidth - V_SCROLLBAR - 2;
    }

    private int detailSummaryTop() {
        return LIST_TOP + rightRowHeight() + 2;
    }

    private int detailSummaryLines() {
        return cachedView == null || cachedStep != selectedStep ? 0 : cachedView.summary().size();
    }

    /** What the summary would take if nothing limited it, less the header and gaps. */
    private int detailSummaryMaxHeight() {
        int rowHeight = rightRowHeight();
        int available = detailBodyBottom() - detailSummaryTop() - rowHeight - 6;
        return Math.max(rowHeight, (int) (available * SUMMARY_MAX_SHARE));
    }

    private int detailSummaryHeight() {
        return Math.min(detailSummaryLines() * rightRowHeight(), detailSummaryMaxHeight());
    }

    private int detailSummaryBottom() {
        return detailSummaryTop() + detailSummaryHeight();
    }

    private int detailSummaryRowCount() {
        return Math.max(1, detailSummaryHeight() / rightRowHeight());
    }

    /** The pinned call-stack header, sitting under whatever the summary was allowed. */
    private int detailHeaderY() {
        return detailSummaryBottom() + 4;
    }

    /** First row of the scrolling body, which holds the call stack and nothing else. */
    private int detailBodyTop() {
        return detailHeaderY() + rightRowHeight() + 2;
    }

    /** Mirrors {@link #rowsBottom()}: the horizontal bar's strip is always reserved. */
    private int detailBodyBottom() {
        return listBottom() - H_SCROLLBAR - 2;
    }

    private int detailBodyRowCount() {
        return Math.max(1, (detailBodyBottom() - detailBodyTop()) / rightRowHeight());
    }

    private int detailContentLeft() {
        return detailX() + 6;
    }

    private int detailHScrollbarY() {
        return detailBodyBottom() + 1;
    }

    private int detailHTrackLeft() {
        return detailX() + 1;
    }

    private int detailHTrackWidth() {
        return detailContentRight() - detailHTrackLeft();
    }

    private int detailContentWidth() {
        return cachedView == null || cachedStep != selectedStep ? 0 : cachedView.contentWidth();
    }

    /**
     * Unlike the timeline, this maximum is exact: a call stack runs to a few hundred lines
     * at most, so every line is measured when the view is built rather than estimated from
     * whatever is on screen — the bar does not resize as you scroll down.
     */
    private int maxDetailHScroll() {
        return Math.max(0, detailContentWidth() - (detailContentRight() - detailContentLeft()));
    }

    /** True when the next row nests deeper, i.e. this scope actually contains something. */
    private boolean hasChildren(int index) {
        List<MTRPayloads.TimelineRow> rows = rows();
        return index + 1 < rows.size() && rows.get(index + 1).depth() > rows.get(index).depth();
    }

    private void collapseToLevel(int level) {
        collapsed.clear();
        List<MTRPayloads.TimelineRow> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).depth() >= level && hasChildren(i)) {
                collapsed.add(i);
            }
        }
        rebuildVisible();
    }

    private void rebuildVisible() {
        List<MTRPayloads.TimelineRow> rows = rows();
        List<Integer> result = new ArrayList<>();

        int i = 0;
        while (i < rows.size()) {
            result.add(i);
            if (collapsed.contains(i)) {
                // Skip the whole subtree: everything after it that nests deeper.
                int depth = rows.get(i).depth();
                int j = i + 1;
                while (j < rows.size() && rows.get(j).depth() > depth) j++;
                i = j;
            } else {
                i++;
            }
        }

        visible = result;
        timelineContentSlots = 0;
        long previousTick = Long.MIN_VALUE;
        for (int rowIndex : visible) {
            MTRPayloads.TimelineRow row = rows.get(rowIndex);
            if (row.tick() != previousTick) {
                timelineContentSlots++;
                previousTick = row.tick();
            }
            timelineContentSlots++;
        }
        clampScroll();
    }

    private int visibleRowCount() {
        return Math.max(1, (rowsBottom() - LIST_TOP) / leftRowHeight());
    }

    /**
     * The largest event-row offset that still leaves the final event fully visible.
     * Tick separators consume a row too, so {@code visible.size() - visibleRowCount()}
     * is only an estimate and can cut off the bottom of a long timeline.
     */
    private int maxTimelineScroll() {
        if (visible.isEmpty()) return 0;

        int slotsOnScreen = visibleRowCount();
        List<MTRPayloads.TimelineRow> rows = rows();
        int start = visible.size() - 1;
        MTRPayloads.TimelineRow last = rows.get(visible.get(start));
        int used = 1;
        if (start == 0 || last.tick() != rows.get(visible.get(start - 1)).tick()) {
            used++;
        }

        while (start > 0) {
            int candidate = start - 1;
            MTRPayloads.TimelineRow row = rows.get(visible.get(candidate));
            int cost = 1;
            if (candidate == 0 || row.tick() != rows.get(visible.get(candidate - 1)).tick()) {
                cost++;
            }
            if (used + cost > slotsOnScreen) break;
            used += cost;
            start = candidate;
        }
        return start;
    }

    private void clampScroll() {
        scroll = Math.clamp(scroll, 0, maxTimelineScroll());
        hScroll = Math.clamp(hScroll, 0, maxHScroll());
    }

    /**
     * Measured from the rows actually on screen rather than all of them: with six figures
     * of rows, running {@code font.width} over every label would cost more than the whole
     * rest of the frame.
     */
    private int maxHScroll() {
        return Math.max(0, measuredContentWidth - (labelRight() - labelLeft()));
    }

    /** Where {@code rowIndex} shows up in the folded view — its nearest visible ancestor. */
    private int visibleIndexOf(int rowIndex) {
        int low = 0;
        int high = visible.size() - 1;
        int best = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (visible.get(mid) <= rowIndex) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private void selectStep(int step) {
        if (step < 0 || step == selectedStep) return;

        selectedStep = step;
        // A new step is new content; carrying the old scroll offsets into it is meaningless.
        summaryScroll = 0;
        detailScroll = 0;
        detailHScroll = 0;

        MTRPayloads.DetailsS2C cached = ClientReplayState.details(step);
        if (cached == null) {
            MTRClientNetworking.requestDetails(step);
        } else {
            // Served from cache, so no packet arrives and nothing else would move the focus.
            ClientReplayState.noteFocus(cached);
        }
    }
    @Override
    public void tick() {
        if (ClientReplayState.timelineRevision() != lastRevision) {
            lastRevision = ClientReplayState.timelineRevision();
            collapsed.clear();
            rebuildVisible();
        }

        int cursorRow = ClientReplayState.cursorRow();
        if (cursorRow != lastCursorRow) {
            lastCursorRow = cursorRow;
            // Stepping should carry the detail column along with it; that is the point
            // of stepping. A manual pick only survives until the replay moves again.
            selectStep(ClientReplayState.currentStep());
            if (MTRClientConfig.followCursor()) {
                scrollTo(cursorRow);
            }
        }
    }

    private void scrollTo(int rowIndex) {
        int target = visibleIndexOf(rowIndex);
        if (target < 0) return;

        int rowsOnScreen = visibleRowCount();
        if (target < scroll || target >= scroll + rowsOnScreen) {
            scroll = target - rowsOnScreen / 2;
        }
        clampScroll();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        // Esc or a screen switch mid-drag means mouseReleased never arrives; without this the
        // player is left with no cursor.
        endDrag();
        super.removed();
    }

    private void endDrag() {
        dragging = Drag.NONE;
        grabRemainder = 0;
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
                GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }


    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Everything the default does is screen-wide paint we do not want — except this,
        // which is the accessibility sound subtitles and has nothing to do with the background.
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int listBottom = listBottom();

        graphics.fill(0, 0, this.width, LIST_TOP - 2, MTRWidgets.CHROME_BG);
        graphics.fill(0, listBottom + 2, this.width, this.height, MTRWidgets.CHROME_BG);

        graphics.text(this.font, headerLine(), 8, 8, MTRWidgets.TEXT);

        MTRWidgets.panel(graphics, LIST_X, LIST_TOP - 2, panelWidth, listBottom - LIST_TOP + 4,
                MTRWidgets.PANEL_BG, MTRWidgets.PANEL_BORDER);

        if (rows().isEmpty()) {
            MTRWidgets.scaledText(graphics,
                    MTRComponent.translatable("mtr.timeline.empty", "No timeline — watch a running replay first"),
                    LIST_X + 8, LIST_TOP + 6, MTRWidgets.TEXT_DIM, this.font, leftFontScale);
        } else {
            graphics.enableScissor(LIST_X + 1, LIST_TOP, contentRight(), rowsBottom());
            drawRows(graphics, mouseX, mouseY, rowsBottom());
            graphics.disableScissor();

            drawScrollbars(graphics);
        }

        drawDetails(graphics, mouseX, mouseY);

        if (settingsOpen) {
            drawSettings(graphics);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (helpOpen) {
            drawHelp(graphics);
        }

        if (searching()) {
            Component status = searchStatus();
            MTRWidgets.scaledText(graphics, status,
                    searchBox.getX() - 6 - leftTextWidth(status), searchBox.getY() + 3,
                    matches.isEmpty() ? MTRWidgets.TEXT_OFF : MTRWidgets.TEXT_ACCENT,
                    this.font, leftFontScale);
        }
    }

    private void drawRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int listBottom) {
        List<MTRPayloads.TimelineRow> rows = rows();
        int cursorRow = ClientReplayState.cursorRow();
        int labelRight = labelRight();
        int contentRight = contentRight();

        long previousTick = Long.MIN_VALUE;
        if (scroll > 0) {
            previousTick = rows.get(visible.get(scroll - 1)).tick();
        }

        int widest = 0;
        int rowHeight = leftRowHeight();
        int y = LIST_TOP;
        for (int i = scroll; i < visible.size() && y < listBottom; i++) {
            int rowIndex = visible.get(i);
            MTRPayloads.TimelineRow row = rows.get(rowIndex);

            if (row.tick() != previousTick) {
                if (y + rowHeight > listBottom) break;
                MTRWidgets.scaledText(graphics,
                        MTRComponent.translatable("mtr.timeline.tick_marker", "── Tick %d ──", row.tick()),
                        labelLeft(), y + 1, MTRWidgets.TEXT_DIM, this.font, leftFontScale);
                previousTick = row.tick();
                y += rowHeight;
                if (y >= listBottom) break;
            }

            boolean hovered = MTRWidgets.isOver(mouseX, mouseY, LIST_X + 1, y, contentRight - LIST_X, rowHeight);
            if (matchSet.contains(rowIndex)) {
                boolean current = matchCursor >= 0 && matchCursor < matches.size()
                        && matches.get(matchCursor) == rowIndex;
                graphics.fill(LIST_X + 1, y, contentRight, y + rowHeight,
                        current ? SEARCH_CURRENT_BG : SEARCH_MATCH_BG);
            }
            if (row.step() == selectedStep) {
                graphics.fill(LIST_X + 1, y, contentRight, y + rowHeight, MTRWidgets.CARD_BG_ACTIVE);
            }
            if (rowIndex == cursorRow) {
                graphics.fill(LIST_X + 1, y, contentRight, y + rowHeight, MTRWidgets.CURSOR_ROW_BG);
            } else if (hovered) {
                graphics.fill(LIST_X + 1, y, contentRight, y + rowHeight, 0x30FFFFFF);
            }

            int foldSlotWidth = leftFoldSlotWidth();
            int contentWidth = rowIndent(row) + foldSlotWidth + leftTextWidth(row.label());
            if (contentWidth > widest) widest = contentWidth;

            graphics.enableScissor(LIST_X + 1, y, labelRight, y + rowHeight);
            int x = labelLeft() + rowIndent(row) - hScroll;
            if (hasChildren(rowIndex)) {
                MTRWidgets.scaledText(graphics,
                        foldSymbol(rowIndex),
                        x, y + 1, MTRWidgets.TEXT_ACCENT, this.font, leftFontScale);
            }
            MTRWidgets.scaledText(graphics, row.label(), x + foldSlotWidth, y + 1,
                    MTRWidgets.opaque(row.color()), this.font, leftFontScale);
            graphics.disableScissor();

            Component jump = Component.literal("#" + row.step() + "↗");
            MTRWidgets.scaledText(graphics, jump, contentRight - 2 - leftTextWidth(jump), y + 1,
                    row.step() == selectedStep ? MTRWidgets.TEXT_ACCENT : MTRWidgets.TEXT_DIM,
                    this.font, leftFontScale);

            y += rowHeight;
        }

        measuredContentWidth = widest;
        if (hScroll > maxHScroll()) hScroll = maxHScroll();
    }

    private int rowIndent(MTRPayloads.TimelineRow row) {
        if (row.depth() <= 0) return 0;
        return Math.max(1, Math.round(row.depth() * INDENT * leftFontScale));
    }

    private void drawScrollbars(GuiGraphicsExtractor graphics) {
        int rowsOnScreen = visibleRowCount();
        int right = listRight();
        int rowsBottom = rowsBottom();
        int maxScroll = maxTimelineScroll();

        if (maxScroll > 0) {
            int trackHeight = rowsBottom - LIST_TOP;
            int thumbHeight = verticalThumbHeight(trackHeight, rowsOnScreen, timelineContentSlots);
            int travel = trackHeight - thumbHeight;
            int thumbY = LIST_TOP + (travel * scroll / Math.max(1, maxScroll));

            graphics.fill(right - V_SCROLLBAR, LIST_TOP, right - 2, rowsBottom, MTRWidgets.SCROLL_TRACK);
            graphics.fill(right - V_SCROLLBAR, thumbY, right - 2, thumbY + thumbHeight,
                    dragging == Drag.TIMELINE_V ? MTRWidgets.SCROLL_THUMB_ACTIVE : MTRWidgets.SCROLL_THUMB);
        }

        int maxH = maxHScroll();
        if (maxH > 0) {
            int trackLeft = hTrackLeft();
            int trackWidth = hTrackWidth();
            int thumbWidth = horizontalThumbWidth(trackWidth);
            int travel = trackWidth - thumbWidth;
            int thumbX = trackLeft + (travel * hScroll / maxH);
            int barY = hScrollbarY();

            graphics.fill(trackLeft, barY, trackLeft + trackWidth, barY + H_SCROLLBAR, MTRWidgets.SCROLL_TRACK);
            graphics.fill(thumbX, barY, thumbX + thumbWidth, barY + H_SCROLLBAR,
                    dragging == Drag.TIMELINE_H ? MTRWidgets.SCROLL_THUMB_ACTIVE : MTRWidgets.SCROLL_THUMB);
        }
    }

    private static int verticalThumbHeight(int trackHeight, int rowsOnScreen, int totalRows) {
        return Math.clamp((long) trackHeight * rowsOnScreen / Math.max(1, totalRows), 12, trackHeight);
    }

    private int horizontalThumbWidth(int trackWidth) {
        return Math.clamp((long) trackWidth * trackWidth / Math.max(1, measuredContentWidth), 16, trackWidth);
    }

    private record DetailLine(FormattedCharSequence text, int color) {}

    /**
     * @param summary      the event's own information, wrapped and pinned above the header
     * @param trace        the call stack, and the only thing that scrolls
     * @param contentWidth widest trace line, so it excludes the wrapped summary
     */
    private record DetailView(List<DetailLine> summary, List<DetailLine> trace, int contentWidth,
                              Component tooltip, String copyText) {}

    private DetailView cachedView;
    private int cachedStep = -1;
    /** Column width the cached lines were trimmed to; a resize or scale change invalidates them. */
    private int cachedPanelWidth = -1;
    private float cachedRightFontScale = -1.0F;

    private DetailView detailView(MTRPayloads.DetailsS2C details) {
        if (cachedView != null && cachedStep == selectedStep && cachedPanelWidth == panelWidth
                && cachedRightFontScale == rightFontScale) {
            return cachedView;
        }

        int inner = Math.max(1, Math.round((panelWidth - V_SCROLLBAR - 12) / rightFontScale));

        List<DetailLine> summary = new ArrayList<>();
        for (FormattedCharSequence line : this.font.split(details.hover(), inner)) {
            summary.add(new DetailLine(line, MTRWidgets.TEXT));
        }

        // Call-stack frames keep their full width and are reached by scrolling sideways —
        // a frame split across two lines is unreadable, and truncating one loses the very
        // part that identifies it.
        List<DetailLine> trace = new ArrayList<>(details.stackTrace().size());
        int contentWidth = 0;
        for (String raw : details.stackTrace()) {
            DetailLine line = new DetailLine(formatStackTraceLine(raw).getVisualOrderText(), MTRWidgets.TEXT_DIM);
            trace.add(line);
            contentWidth = Math.max(contentWidth, rightTextWidth(line.text()));
        }

        MutableComponent tooltip = MTRComponent.translatable(
                        "mtr.stacktrace.tooltip_title", "Step #%d StackTrace:\n", selectedStep)
                .withStyle(ChatFormatting.GOLD);

        int shown = Math.min(details.stackTrace().size(), TOOLTIP_TRACE_LINES);
        for (int i = 0; i < shown; i++) {
            tooltip.append(formatStackTraceLine(details.stackTrace().get(i)))
                    .append(Component.literal(i < shown - 1 ? "\n" : ""));
        }
        if (details.stackTrace().size() > shown) {
            tooltip.append(Component.literal("\n"))
                    .append(MTRComponent.translatable("mtr.stacktrace.tooltip_more", "... and %d more lines",
                                    details.stackTrace().size() - shown)
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }

        cachedView = new DetailView(summary, trace, contentWidth, tooltip,
                String.join("\n", details.stackTrace()));
        cachedStep = selectedStep;
        cachedPanelWidth = panelWidth;
        cachedRightFontScale = rightFontScale;
        return cachedView;
    }

    private void drawDetails(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = detailX();
        int listBottom = listBottom();
        MTRWidgets.panel(graphics, x, LIST_TOP - 2, panelWidth, listBottom - LIST_TOP + 4,
                MTRWidgets.PANEL_BG, MTRWidgets.PANEL_BORDER);

        stackHeaderRect = ScreenRectangle.empty();

        if (selectedStep < 0) {
            MTRWidgets.scaledText(graphics,
                    MTRComponent.translatable("mtr.timeline.no_selection", "Select a row to inspect it"),
                    x + 6, LIST_TOP + 1, MTRWidgets.TEXT_DIM, this.font, rightFontScale);
            return;
        }

        MTRWidgets.scaledText(graphics,
                MTRComponent.translatable("mtr.timeline.details", "Step #%d", selectedStep),
                x + 6, LIST_TOP + 1, MTRWidgets.TEXT_ACCENT, this.font, rightFontScale);

        MTRPayloads.DetailsS2C details = ClientReplayState.details(selectedStep);
        if (details == null) {
            MTRWidgets.scaledText(graphics,
                    MTRComponent.translatable("mtr.timeline.loading", "Loading…"),
                    x + 6, LIST_TOP + rightRowHeight() + 1, MTRWidgets.TEXT_DIM,
                    this.font, rightFontScale);
            return;
        }

        DetailView view = detailView(details);

        clampSummaryScroll();

        // The event's own information, in its own scroll region above the call stack.
        int summaryTop = detailSummaryTop();
        int summaryBottom = detailSummaryBottom();
        graphics.enableScissor(x + 1, summaryTop, detailContentRight(), summaryBottom);
        int summaryY = summaryTop;
        for (int i = summaryScroll; i < view.summary().size() && summaryY < summaryBottom; i++) {
            DetailLine line = view.summary().get(i);
            MTRWidgets.scaledText(graphics, line.text(), x + 6, summaryY + 1,
                    line.color(), this.font, rightFontScale);
            summaryY += rightRowHeight();
        }
        graphics.disableScissor();
        drawSummaryScrollbar(graphics, view.summary().size(), summaryTop, summaryBottom);

        int headerY = detailHeaderY();
        Component header = copiedRecently()
                ? MTRComponent.translatable("mtr.timeline.stacktrace_copied", "Call stack — copied!")
                : MTRComponent.translatable("mtr.timeline.stacktrace", "Call stack ⧉");
        stackHeaderRect = new ScreenRectangle(x + 6, headerY - 1, panelWidth - 12, rightRowHeight() + 2);

        boolean headerHovered = MTRWidgets.isOver(mouseX, mouseY,
                stackHeaderRect.left(), stackHeaderRect.top(), stackHeaderRect.width(), stackHeaderRect.height());
        if (headerHovered) {
            graphics.fill(stackHeaderRect.left(), stackHeaderRect.top(),
                    stackHeaderRect.left() + stackHeaderRect.width(),
                    stackHeaderRect.top() + stackHeaderRect.height(), 0x30FFFFFF);
        }
        MTRWidgets.scaledText(graphics, header, x + 6, headerY,
                copiedRecently() ? MTRWidgets.TEXT_ON : MTRWidgets.TEXT_ACCENT,
                this.font, rightFontScale);

        clampDetailScroll();

        // The detail column uses its own scaled row metrics and clipping.
        int bodyTop = detailBodyTop();
        int bodyBottom = detailBodyBottom();
        graphics.enableScissor(x + 1, bodyTop, detailContentRight(), bodyBottom);
        int lineY = bodyTop;
        for (int i = detailScroll; i < view.trace().size() && lineY < bodyBottom; i++) {
            DetailLine line = view.trace().get(i);
            MTRWidgets.scaledText(graphics, line.text(), detailContentLeft() - detailHScroll, lineY + 1,
                    line.color(), this.font, rightFontScale);
            lineY += rightRowHeight();
        }
        graphics.disableScissor();

        drawDetailScrollbars(graphics, view.trace().size(), bodyBottom);

        // Vanilla draws this during the outer render pass, so it lands above everything.
        stackTooltip.set(Tooltip.create(view.tooltip()));
        stackTooltip.refreshTooltipForNextRenderPass(graphics, mouseX, mouseY, headerHovered, false, stackHeaderRect);
    }

    private int detailLineCount() {
        return cachedView == null || cachedStep != selectedStep ? 0 : cachedView.trace().size();
    }

    private void clampDetailScroll() {
        detailScroll = Math.clamp(detailScroll, 0, Math.max(0, detailLineCount() - detailBodyRowCount()));
        detailHScroll = Math.clamp(detailHScroll, 0, maxDetailHScroll());
    }

    private void clampSummaryScroll() {
        summaryScroll = Math.clamp(summaryScroll, 0, Math.max(0, detailSummaryLines() - detailSummaryRowCount()));
    }

    private void drawSummaryScrollbar(GuiGraphicsExtractor graphics, int lineCount, int top, int bottom) {
        int rowsOnScreen = detailSummaryRowCount();
        if (lineCount <= rowsOnScreen) return;

        int right = detailX() + panelWidth;
        int trackHeight = bottom - top;
        int thumbHeight = verticalThumbHeight(trackHeight, rowsOnScreen, lineCount);
        int travel = trackHeight - thumbHeight;
        int thumbY = top + (travel * summaryScroll / Math.max(1, lineCount - rowsOnScreen));

        graphics.fill(right - V_SCROLLBAR, top, right - 2, bottom, MTRWidgets.SCROLL_TRACK);
        graphics.fill(right - V_SCROLLBAR, thumbY, right - 2, thumbY + thumbHeight,
                dragging == Drag.SUMMARY_V ? MTRWidgets.SCROLL_THUMB_ACTIVE : MTRWidgets.SCROLL_THUMB);
    }

    private void drawDetailScrollbars(GuiGraphicsExtractor graphics, int lineCount, int bodyBottom) {
        int rowsOnScreen = detailBodyRowCount();
        if (lineCount > rowsOnScreen) {
            int right = detailX() + panelWidth;
            int top = detailBodyTop();
            int trackHeight = bodyBottom - top;
            int thumbHeight = verticalThumbHeight(trackHeight, rowsOnScreen, lineCount);
            int travel = trackHeight - thumbHeight;
            int thumbY = top + (travel * detailScroll / Math.max(1, lineCount - rowsOnScreen));

            graphics.fill(right - V_SCROLLBAR, top, right - 2, bodyBottom, MTRWidgets.SCROLL_TRACK);
            graphics.fill(right - V_SCROLLBAR, thumbY, right - 2, thumbY + thumbHeight,
                    dragging == Drag.DETAIL_V ? MTRWidgets.SCROLL_THUMB_ACTIVE : MTRWidgets.SCROLL_THUMB);
        }

        int maxH = maxDetailHScroll();
        if (maxH > 0) {
            int trackLeft = detailHTrackLeft();
            int trackWidth = detailHTrackWidth();
            int thumbWidth = Math.clamp((long) trackWidth * trackWidth / Math.max(1, detailContentWidth()),
                    16, trackWidth);
            int travel = trackWidth - thumbWidth;
            int thumbX = trackLeft + (travel * detailHScroll / maxH);
            int barY = detailHScrollbarY();

            graphics.fill(trackLeft, barY, trackLeft + trackWidth, barY + H_SCROLLBAR, MTRWidgets.SCROLL_TRACK);
            graphics.fill(thumbX, barY, thumbX + thumbWidth, barY + H_SCROLLBAR,
                    dragging == Drag.DETAIL_H ? MTRWidgets.SCROLL_THUMB_ACTIVE : MTRWidgets.SCROLL_THUMB);
        }
    }

    private boolean copiedRecently() {
        return System.currentTimeMillis() - copiedAtMillis < 1200;
    }

    private Component headerLine() {
        if (!ClientReplayState.isWatching()) {
            return MTRComponent.translatable("mtr.timeline.not_watching", "Not watching a replay");
        }
        return MTRComponent.translatable("mtr.timeline.header",
                "%s  ·  Tick %d / %d  ·  Step %d / %d  ·  %d rows (%d shown)",
                ClientReplayState.subscribed(),
                ClientReplayState.tick(), ClientReplayState.totalTick(),
                Math.max(0, ClientReplayState.cursorRow() + 1), ClientReplayState.totalRows(),
                rows().size(), visible.size());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean control = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;

        if (helpOpen) {
            // While the manual is up it owns the keyboard; any key puts it away.
            helpOpen = false;
            return true;
        }

        if (settingsOpen) {
            if (event.isEscape()) {
                setSettingsOpen(false);
                return true;
            }
            return super.keyPressed(event);
        }

        if (ClientReplayState.cameraFollow() && !searching() && matchesHideGui(event)) {
            if (!this.minecraft.gui.hud.isHidden()) this.minecraft.gui.hud.toggle();
            TimelineAutoHide.markHidden();
            this.minecraft.gui.setScreen(null);
            return true;
        }

        if (control && event.key() == GLFW.GLFW_KEY_F) {
            openSearch();
            return true;
        }

        if (searching()) {
            switch (event.key()) {
                case GLFW.GLFW_KEY_ESCAPE -> {
                    // Swallow it: the bar closes, the screen stays.
                    closeSearch();
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_F4 -> {
                    stepMatch(shift ? -1 : 1);
                    return true;
                }
                default -> { }
            }
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubled) {
        if (helpOpen) {
            helpOpen = false;
            return true;
        }
        if (super.mouseClicked(event, doubled)) return true;

        if (settingsOpen) {
            return true;
        }

        if ((event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                || event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) && beginCameraDrag(event)) {
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && beginGrab(event.x(), event.y())) {
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && isViewportDoubleClick(event)) {
            if (retargetAt(event.x(), event.y())) {
                return true;
            }
        }

        // Scrollbars first: they sit on top of the lists they belong to.
        if (overTimelineVBar(event.x(), event.y())) {
            dragging = Drag.TIMELINE_V;
            applyTimelineVDrag(event.y());
            return true;
        }
        if (overTimelineHBar(event.x(), event.y())) {
            dragging = Drag.TIMELINE_H;
            applyTimelineHDrag(event.x());
            return true;
        }
        if (overSummaryVBar(event.x(), event.y())) {
            dragging = Drag.SUMMARY_V;
            applySummaryVDrag(event.y());
            return true;
        }
        if (overDetailVBar(event.x(), event.y())) {
            dragging = Drag.DETAIL_V;
            applyDetailVDrag(event.y());
            return true;
        }
        if (overDetailHBar(event.x(), event.y())) {
            dragging = Drag.DETAIL_H;
            applyDetailHDrag(event.x());
            return true;
        }

        if (MTRWidgets.isOver(event.x(), event.y(), stackHeaderRect.left(), stackHeaderRect.top(),
                stackHeaderRect.width(), stackHeaderRect.height())) {
            DetailView view = cachedView;
            if (view != null) {
                this.minecraft.keyboardHandler.setClipboard(view.copyText());
                copiedAtMillis = System.currentTimeMillis();
            }
            return true;
        }

        int rowsBottom = rowsBottom();
        if (event.y() < LIST_TOP || event.y() >= rowsBottom
                || event.x() < LIST_X || event.x() >= contentRight()) {
            return false;
        }

        int rowIndex = rowAt(event.y(), rowsBottom);
        if (rowIndex < 0) return false;

        MTRPayloads.TimelineRow row = rows().get(rowIndex);

        // The jump box is pinned, so it is tested before anything that scrolls.
        if (event.x() >= contentRight() - JUMP_ZONE) {
            MTRClientNetworking.jump(row.step());
            return true;
        }

        int toggleX = labelLeft() + rowIndent(row) - hScroll;
        if (hasChildren(rowIndex) && event.x() >= toggleX
                && event.x() < toggleX + leftFoldSlotWidth()) {
            if (!collapsed.remove(rowIndex)) {
                collapsed.add(rowIndex);
            }
            rebuildVisible();
            return true;
        }

        selectStep(row.step());
        if (doubled) {
            MTRClientNetworking.jump(row.step());
        }
        return true;
    }

    /**
     * Reverses the draw loop's layout, tick separators included — they occupy a row of
     * their own, so a naive divide would select the wrong event.
     */
    private int rowAt(double mouseY, int listBottom) {
        List<MTRPayloads.TimelineRow> rows = rows();

        long previousTick = Long.MIN_VALUE;
        if (scroll > 0) {
            previousTick = rows.get(visible.get(scroll - 1)).tick();
        }

        int rowHeight = leftRowHeight();
        int y = LIST_TOP;
        for (int i = scroll; i < visible.size() && y < listBottom; i++) {
            int rowIndex = visible.get(i);
            MTRPayloads.TimelineRow row = rows.get(rowIndex);

            if (row.tick() != previousTick) {
                if (y + rowHeight > listBottom) break;
                previousTick = row.tick();
                y += rowHeight;
                if (y >= listBottom) break;
            }

            if (mouseY >= y && mouseY < y + rowHeight) return rowIndex;
            y += rowHeight;
        }
        return -1;
    }

    /**
     * {@code mouseScrolled} carries no modifier state in 26.1 — modifiers only ride on
     * key and button events — so ask the window whether the key is physically down.
     */
    private boolean isKeyHeld(int left, int right) {
        return InputConstants.isKeyDown(this.minecraft.getWindow(), left)
                || InputConstants.isKeyDown(this.minecraft.getWindow(), right);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (settingsOpen) {
            return true;
        }

        // A tilt wheel scrolls sideways directly; control plus the normal wheel is the
        // fallback for the mice that do not have one.
        if (scrollX != 0) {
            if (mouseX >= detailX()) {
                detailHScroll -= (int) Math.signum(scrollX) * H_SCROLL_STEP;
                clampDetailScroll();
            } else {
                hScroll -= (int) Math.signum(scrollX) * H_SCROLL_STEP;
                clampScroll();
            }
            return true;
        }

        if (scrollY == 0) return false;

        if (isKeyHeld(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            // Shift turns the wheel into replay control, matching the hotbar panel.
            boolean forward = scrollY > 0;
            if (MTRClientConfig.invertScroll()) forward = !forward;
            MTRClientNetworking.step(forward);
            return true;
        }

        // The middle gap is the camera's.
        if (overViewport(mouseX, mouseY) && cameraDolly(scrollY)) {
            return true;
        }

        // Whichever column the pointer is over is the one that scrolls, and control turns
        // the wheel sideways in either of them.
        if (mouseX >= detailX()) {
            // The summary is a region of its own, so the wheel follows the pointer into it.
            if (mouseY < detailSummaryBottom()) {
                summaryScroll -= (int) Math.signum(scrollY) * 3;
                clampSummaryScroll();
                return true;
            }
            if (isKeyHeld(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
                detailHScroll -= (int) Math.signum(scrollY) * H_SCROLL_STEP;
            } else {
                detailScroll -= (int) Math.signum(scrollY) * 3;
            }
            clampDetailScroll();
            return true;
        }

        if (isKeyHeld(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            hScroll -= (int) Math.signum(scrollY) * H_SCROLL_STEP;
            clampScroll();
            return true;
        }

        scroll -= (int) Math.signum(scrollY) * 3;
        clampScroll();
        return true;
    }

    // ── scrollbar dragging ───────────────────────────────────────────────────

    private boolean overTimelineVBar(double mouseX, double mouseY) {
        if (maxTimelineScroll() <= 0) return false;
        return MTRWidgets.isOver(mouseX, mouseY, listRight() - V_SCROLLBAR, LIST_TOP,
                V_SCROLLBAR, rowsBottom() - LIST_TOP);
    }

    private boolean overTimelineHBar(double mouseX, double mouseY) {
        if (maxHScroll() <= 0) return false;
        return MTRWidgets.isOver(mouseX, mouseY, hTrackLeft(), hScrollbarY(), hTrackWidth(), H_SCROLLBAR);
    }

    private boolean overSummaryVBar(double mouseX, double mouseY) {
        if (detailSummaryLines() <= detailSummaryRowCount()) return false;
        return MTRWidgets.isOver(mouseX, mouseY, detailX() + panelWidth - V_SCROLLBAR, detailSummaryTop(),
                V_SCROLLBAR, detailSummaryHeight());
    }

    private boolean overDetailVBar(double mouseX, double mouseY) {
        if (detailLineCount() <= detailBodyRowCount()) return false;
        return MTRWidgets.isOver(mouseX, mouseY, detailX() + panelWidth - V_SCROLLBAR, detailBodyTop(),
                V_SCROLLBAR, detailBodyBottom() - detailBodyTop());
    }

    private boolean overDetailHBar(double mouseX, double mouseY) {
        if (maxDetailHScroll() <= 0) return false;
        return MTRWidgets.isOver(mouseX, mouseY, detailHTrackLeft(), detailHScrollbarY(),
                detailHTrackWidth(), H_SCROLLBAR);
    }

    /**
     * Maps a pointer position on a track to a scroll offset, treating the grab point as
     * the middle of the thumb so the thumb lands under the cursor rather than jumping.
     */
    private static int scrollFromTrack(double pointer, int trackStart, int trackLength, int thumbLength, int maxScroll) {
        int travel = trackLength - thumbLength;
        if (travel <= 0) return 0;

        double offset = pointer - trackStart - thumbLength / 2.0;
        return Math.clamp(Math.round(offset / travel * maxScroll), 0, maxScroll);
    }

    private void applyTimelineVDrag(double mouseY) {
        int rowsOnScreen = visibleRowCount();
        int maxScroll = maxTimelineScroll();
        int trackHeight = rowsBottom() - LIST_TOP;
        scroll = scrollFromTrack(mouseY, LIST_TOP, trackHeight,
                verticalThumbHeight(trackHeight, rowsOnScreen, timelineContentSlots), maxScroll);
    }

    private void applyTimelineHDrag(double mouseX) {
        int trackWidth = hTrackWidth();
        hScroll = scrollFromTrack(mouseX, hTrackLeft(), trackWidth,
                horizontalThumbWidth(trackWidth), maxHScroll());
    }

    private void applyDetailVDrag(double mouseY) {
        int rowsOnScreen = detailBodyRowCount();
        int lineCount = detailLineCount();
        int maxScroll = Math.max(0, lineCount - rowsOnScreen);
        int top = detailBodyTop();
        int trackHeight = detailBodyBottom() - top;
        detailScroll = scrollFromTrack(mouseY, top, trackHeight,
                verticalThumbHeight(trackHeight, rowsOnScreen, lineCount), maxScroll);
    }

    private void applySummaryVDrag(double mouseY) {
        int rowsOnScreen = detailSummaryRowCount();
        int lineCount = detailSummaryLines();
        int trackHeight = detailSummaryHeight();
        summaryScroll = scrollFromTrack(mouseY, detailSummaryTop(), trackHeight,
                verticalThumbHeight(trackHeight, rowsOnScreen, lineCount),
                Math.max(0, lineCount - rowsOnScreen));
    }

    private void applyDetailHDrag(double mouseX) {
        int trackWidth = detailHTrackWidth();
        int thumbWidth = Math.clamp((long) trackWidth * trackWidth / Math.max(1, detailContentWidth()),
                16, trackWidth);
        detailHScroll = scrollFromTrack(mouseX, detailHTrackLeft(), trackWidth, thumbWidth, maxDetailHScroll());
    }

    @Override
    public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
        if (settingsOpen) {
            return super.mouseDragged(event, dragX, dragY);
        }

        switch (dragging) {
            case TIMELINE_V -> applyTimelineVDrag(event.y());
            case TIMELINE_H -> applyTimelineHDrag(event.x());
            case DETAIL_V -> applyDetailVDrag(event.y());
            case DETAIL_H -> applyDetailHDrag(event.x());
            case SUMMARY_V -> applySummaryVDrag(event.y());
            case CAMERA_ORBIT -> {
                camera.orbit(dragX, dragY);
                applyCamera();
            }
            case CAMERA_PAN -> {
                camera.pan(dragX, dragY, viewportHeight(), fovDegrees());
                applyCamera();
            }
            case GRAB_TIMELINE, GRAB_DETAIL, GRAB_SUMMARY -> applyGrab(dragX, dragY);
            case NONE -> {
                return super.mouseDragged(event, dragX, dragY);
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent event) {
        if (settingsOpen) {
            return super.mouseReleased(event);
        }

        if (dragging != Drag.NONE) {
            endDrag();
            return true;
        }
        return super.mouseReleased(event);
    }


    private boolean matchesHideGui(KeyEvent event) {
        return this.minecraft.options.keyToggleGui.matches(event);
    }

    // Manual---

    private void drawHelp(GuiGraphicsExtractor graphics) {
        List<MTRHelpText.Line> help = MTRHelpText.lines(MTRHelpText.TIMELINE_KEY, MTRHelpText.TIMELINE_FALLBACK);
        if (help.isEmpty()) return;

        int lineHeight = this.font.lineHeight + 2;
        int widest = 0;
        for (MTRHelpText.Line line : help) {
            widest = Math.max(widest, this.font.width(line.text()));
        }

        Component hint = MTRComponent.translatable("mtr.help.close_hint", "Any key or click closes this");
        widest = Math.max(widest, this.font.width(hint));

        int gaps = 0;
        for (int i = 1; i < help.size(); i++) {
            if (help.get(i).heading()) gaps++;
        }
        gaps += 1;

        int padding = 10;
        int boxWidth = Math.min(this.width - 20, widest + padding * 2 + 8);
        int boxHeight = (help.size() + gaps) * lineHeight + padding * 2;
        int boxX = (this.width - boxWidth) / 2;
        int boxY = Math.max(4, (this.height - boxHeight) / 2);

        graphics.fill(0, 0, this.width, this.height, 0xA0000000);
        MTRWidgets.panel(graphics, boxX, boxY, boxWidth, boxHeight,
                MTRWidgets.PANEL_BG, MTRWidgets.PANEL_BORDER);

        int y = boxY + padding;
        for (int i = 0; i < help.size(); i++) {
            MTRHelpText.Line line = help.get(i);
            if (line.heading() && i > 0) y += lineHeight;
            graphics.text(this.font, Component.literal(line.text()),
                    boxX + padding + (line.heading() ? 0 : 8), y,
                    line.heading() ? MTRWidgets.TEXT_ACCENT : MTRWidgets.TEXT);
            y += lineHeight;
        }
        y += lineHeight;
        graphics.text(this.font, hint, boxX + padding, y, MTRWidgets.TEXT_DIM);
    }

    private void drawSettings(@NonNull GuiGraphicsExtractor graphics) {
        settingsBoxWidth = Math.min(300, Math.max(220, this.width - 20));
        settingsBoxHeight = 210;
        settingsBoxX = (this.width - settingsBoxWidth) / 2;
        settingsBoxY = Math.max(4, (this.height - settingsBoxHeight) / 2);

        int contentX = settingsBoxX + 16;
        int widthControlY = settingsBoxY + 43;
        int fontControlY = settingsBoxY + 83;
        int rightFontControlY = settingsBoxY + 123;
        int controlWidth = settingsBoxWidth - 32;

        graphics.fill(0, 0, this.width, this.height, 0xA0000000);
        MTRWidgets.panel(graphics, settingsBoxX, settingsBoxY, settingsBoxWidth, settingsBoxHeight,
                         MTRWidgets.PANEL_BG, MTRWidgets.PANEL_BORDER);

        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.title", "时间轴设置"),
                contentX, settingsBoxY + 10, MTRWidgets.TEXT_ACCENT);
        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.sidebar_width", "侧边栏宽度"),
                contentX, settingsBoxY + 29, MTRWidgets.TEXT);
        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.range", "范围: %d - %d", MIN_PANEL_WIDTH,
                        settingsMaxWidth()),
                settingsBoxX + settingsBoxWidth - 112, settingsBoxY + 30, MTRWidgets.TEXT_DIM);
        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.left_font_size", "左侧字体大小"),
                contentX, settingsBoxY + 69, MTRWidgets.TEXT);
        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.left_font_size_range", "范围: %d%% - %d%%",
                        FONT_MIN_PERCENT, FONT_MAX_PERCENT),
                settingsBoxX + settingsBoxWidth - 112, settingsBoxY + 70, MTRWidgets.TEXT_DIM);
        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.right_font_size", "右侧字体大小"),
                contentX, settingsBoxY + 109, MTRWidgets.TEXT);
        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.right_font_size_range", "范围: %d%% - %d%%",
                        FONT_MIN_PERCENT, FONT_MAX_PERCENT),
                settingsBoxX + settingsBoxWidth - 112, settingsBoxY + 110, MTRWidgets.TEXT_DIM);

        sidebarWidthSlider.setX(contentX);
        sidebarWidthSlider.setY(widthControlY);
        sidebarWidthSlider.setWidth(controlWidth);
        sidebarWidthSlider.setHeight(20);

        sidebarWidthInput.setX(contentX);
        sidebarWidthInput.setY(widthControlY);
        sidebarWidthInput.setWidth(controlWidth);
        sidebarWidthInput.setHeight(20);
        sidebarWidthInput.setHint(MTRComponent.translatable(
                "mtr.timeline.settings.sidebar_width_hint", "输入 %d - %d", MIN_PANEL_WIDTH,
                settingsMaxWidth()));

        leftFontSlider.setX(contentX);
        leftFontSlider.setY(fontControlY);
        leftFontSlider.setWidth(controlWidth);
        leftFontSlider.setHeight(20);

        leftFontInput.setX(contentX);
        leftFontInput.setY(fontControlY);
        leftFontInput.setWidth(controlWidth);
        leftFontInput.setHeight(20);
        leftFontInput.setHint(MTRComponent.translatable(
                "mtr.timeline.settings.left_font_size_hint", "输入 %d - %d%%",
                FONT_MIN_PERCENT, FONT_MAX_PERCENT));

        rightFontSlider.setX(contentX);
        rightFontSlider.setY(rightFontControlY);
        rightFontSlider.setWidth(controlWidth);
        rightFontSlider.setHeight(20);

        rightFontInput.setX(contentX);
        rightFontInput.setY(rightFontControlY);
        rightFontInput.setWidth(controlWidth);
        rightFontInput.setHeight(20);
        rightFontInput.setHint(MTRComponent.translatable(
                "mtr.timeline.settings.right_font_size_hint", "输入 %d - %d%%",
                FONT_MIN_PERCENT, FONT_MAX_PERCENT));

        settingsModeButton.setX(contentX);
        settingsModeButton.setY(settingsBoxY + 157);
        settingsModeButton.setMessage(settingsModeLabel());

        settingsDoneButton.setX(settingsBoxX + settingsBoxWidth - 96);
        settingsDoneButton.setY(settingsBoxY + 157);

        graphics.text(this.font,
                MTRComponent.translatable("mtr.timeline.settings.current",
                        "侧边栏：%d · 左侧字体：%d%% · 右侧字体：%d%%",
                settingsCurrentWidth(), leftFontPercent(), rightFontPercent()),
                contentX, settingsBoxY + 188, MTRWidgets.TEXT_DIM);
    }

    // Grab-scrolling---

    private boolean beginGrab(double mouseX, double mouseY) {
        Drag target = grabRegionAt(mouseX, mouseY);
        if (target == Drag.NONE) return false;

        dragging = target;
        grabRemainder = 0;
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
                GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        return true;
    }

    private Drag grabRegionAt(double mouseX, double mouseY) {
        if (mouseX >= LIST_X && mouseX < contentRight()
                && mouseY >= LIST_TOP && mouseY < rowsBottom()) {
            return Drag.GRAB_TIMELINE;
        }
        if (mouseX >= detailX() && mouseX < detailContentRight()) {
            if (mouseY >= detailSummaryTop() && mouseY < detailSummaryBottom()) {
                return Drag.GRAB_SUMMARY;
            }
            if (mouseY >= detailBodyTop() && mouseY < detailBodyBottom()) {
                return Drag.GRAB_DETAIL;
            }
        }
        return Drag.NONE;
    }

    private void applyGrab(double dragX, double dragY) {
        grabRemainder += dragY;

        switch (dragging) {
            case GRAB_TIMELINE -> {
                int rowHeight = leftRowHeight();
                int rows = (int) (grabRemainder / rowHeight);
                grabRemainder -= rows * rowHeight;
                scroll -= rows;
                hScroll -= (int) Math.round(dragX);
                clampScroll();
            }
            case GRAB_DETAIL -> {
                int rowHeight = rightRowHeight();
                int rows = (int) (grabRemainder / rowHeight);
                grabRemainder -= rows * rowHeight;
                detailScroll -= rows;
                detailHScroll -= (int) Math.round(dragX);
                clampDetailScroll();
            }
            case GRAB_SUMMARY -> {
                int rowHeight = rightRowHeight();
                int rows = (int) (grabRemainder / rowHeight);
                grabRemainder -= rows * rowHeight;
                summaryScroll -= rows;
                clampSummaryScroll();
            }
            default -> { }
        }
    }

    // Search---

    private boolean searching() {
        return searchBox != null;
    }

    private void openSearch() {
        if (searching()) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return;
        }

        int width = Math.clamp(panelWidth - 24, 60, SEARCH_WIDTH);
        int x = detailX() - V_SCROLLBAR - 4 - width;
        searchBox = new EditBox(this.font, x, LIST_TOP, width, SEARCH_HEIGHT,
                MTRComponent.translatable("mtr.timeline.search", "Search"));
        searchBox.setMaxLength(128);
        searchBox.setHint(MTRComponent.translatable("mtr.timeline.search_hint", "Search labels..."));
        searchBox.setResponder(query -> {
            recomputeMatches(query);
            if (!matches.isEmpty()) goToMatch();
        });
        addRenderableWidget(searchBox);
        setFocused(searchBox);
        searchBox.setFocused(true);
    }

    private void closeSearch() {
        if (!searching()) return;
        removeWidget(searchBox);
        searchBox = null;
        matches = List.of();
        matchSet = Set.of();
        matchCursor = -1;
        setFocused(null);
    }

    /**
     * Lower-cased labels for matching. Resolving a {@link Component} per row per keystroke is
     * what this avoids -- the list runs to thousands of rows.
     */
    private List<String> searchIndex() {
        int revision = ClientReplayState.timelineRevision();
        if (searchIndex == null || searchIndexRevision != revision) {
            List<MTRPayloads.TimelineRow> rows = rows();
            List<String> index = new ArrayList<>(rows.size());
            for (MTRPayloads.TimelineRow row : rows) {
                index.add(row.label().getString().toLowerCase(Locale.ROOT));
            }
            searchIndex = index;
            searchIndexRevision = revision;
        }
        return searchIndex;
    }

    private void recomputeMatches(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            matches = List.of();
            matchSet = Set.of();
            matchCursor = -1;
            return;
        }

        List<String> index = searchIndex();
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < index.size(); i++) {
            if (index.get(i).contains(needle)) found.add(i);
        }
        matches = found;
        matchSet = new HashSet<>(found);
        matchCursor = found.isEmpty() ? -1 : 0;
    }

    private void stepMatch(int delta) {
        if (matches.isEmpty()) return;
        matchCursor = Math.floorMod(matchCursor + delta, matches.size());
        goToMatch();
    }

    private void goToMatch() {
        if (matchCursor < 0 || matchCursor >= matches.size()) return;

        int rowIndex = matches.get(matchCursor);
        // A hit inside a collapsed subtree is not on screen; open its ancestors first.
        revealRow(rowIndex);

        List<MTRPayloads.TimelineRow> rows = rows();
        if (rowIndex < rows.size()) {
            selectStep(rows.get(rowIndex).step());
        }
        scrollTo(rowIndex);
    }

    private void revealRow(int rowIndex) {
        List<MTRPayloads.TimelineRow> rows = rows();
        if (rowIndex < 0 || rowIndex >= rows.size()) return;

        int wantedDepth = rows.get(rowIndex).depth();
        boolean changed = false;
        for (int i = rowIndex - 1; i >= 0 && wantedDepth > 0; i--) {
            int depth = rows.get(i).depth();
            if (depth < wantedDepth) {
                wantedDepth = depth;
                changed |= collapsed.remove(i);
            }
        }
        if (changed) rebuildVisible();
    }

    private Component searchStatus() {
        if (searchBox == null || searchBox.getValue().trim().isEmpty()) return Component.empty();
        if (matches.isEmpty()) {
            return MTRComponent.translatable("mtr.timeline.search_none", "no match");
        }
        return Component.literal((matchCursor + 1) + " / " + matches.size());
    }

    private boolean overViewport(double mouseX, double mouseY) {
        return mouseX >= listRight() && mouseX < detailX()
                && mouseY >= LIST_TOP - 2 && mouseY < listBottom() + 2;
    }

    /**
     * Own double-click detection for the viewport.
     *
     * <p>Vanilla's {@code doubled} flag is no use here: {@code MouseHandler} only remembers a
     * click as the first half of a double if {@code mouseClicked} <em>consumed</em> it, and a
     * plain left click in the empty middle is consumed by nothing. It also has no position
     * tolerance — any two clicks within the window count, however far apart — so this checks
     * distance as well.
     */
    private boolean isViewportDoubleClick(MouseButtonEvent event) {
        if (!overViewport(event.x(), event.y())) return false;

        long now = Util.getMillis();
        boolean doubled = now - lastViewportClickMillis <= DOUBLE_CLICK_MS
                && Math.abs(event.x() - lastViewportClickX) <= DOUBLE_CLICK_SLOP
                && Math.abs(event.y() - lastViewportClickY) <= DOUBLE_CLICK_SLOP;

        // Consume the pair, so a third fast click starts a new one instead of firing again.
        lastViewportClickMillis = doubled ? 0 : now;
        lastViewportClickX = event.x();
        lastViewportClickY = event.y();
        return doubled;
    }

    private int viewportHeight() {
        return Math.max(1, listBottom() + 2 - (LIST_TOP - 2));
    }

    private double fovDegrees() {
        return this.minecraft.options.fov().get();
    }
    private boolean syncCamera() {
        LocalPlayer player = this.minecraft.player;
        Vec3 serverFocus = ClientReplayState.cameraFocus();
        if (player == null || serverFocus == null) return false;

        Vec3 focus = camera.isPrimed() && serverFocus.equals(lastServerFocus) ? camera.focus() : serverFocus;
        lastServerFocus = serverFocus;
        camera.reset(player.getEyePosition(), focus);
        return true;
    }
    private boolean beginCameraDrag(MouseButtonEvent event) {
        if (!ClientReplayState.cameraFollow() || !overViewport(event.x(), event.y())) return false;
        if (!syncCamera()) return false;
        dragging = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? Drag.CAMERA_PAN : Drag.CAMERA_ORBIT;
        GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().handle(),
                GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_DISABLED);
        return true;
    }
    private boolean cameraDolly(double scrollY) {
        if (!ClientReplayState.cameraFollow() || !syncCamera()) return false;

        camera.dolly(scrollY);
        applyCamera();
        return true;
    }
    private boolean retargetAt(double mouseX, double mouseY) {
        if (!ClientReplayState.cameraFollow() || !overViewport(mouseX, mouseY)) return false;

        LocalPlayer player = this.minecraft.player;
        if (player == null || this.minecraft.level == null || !syncCamera()) return false;

        Vec3 eye = player.getEyePosition();
        Vec3 direction = camera.rayThrough(mouseX, mouseY, this.width, this.height, fovDegrees());
        BlockHitResult hit = this.minecraft.level.clip(new ClipContext(
                eye, eye.add(direction.scale(RETARGET_RANGE)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return false;

        camera.reset(eye, Vec3.atCenterOf(hit.getBlockPos()));
        applyCamera();
        return true;
    }


    /**
     * Moves the spectating player to where the camera now sits.
     *
     * <p>Done client-side rather than through a packet so the view tracks the mouse at frame
     * rate instead of at round-trip rate. Vanilla's own movement packet carries the result to
     * the server on the next tick.
     */
    private void applyCamera() {
        LocalPlayer player = this.minecraft.player;
        if (player == null || !camera.isPrimed()) return;

        Vec3 eye = camera.eyePosition();
        player.snapTo(eye.x, eye.y - player.getEyeHeight(), eye.z, camera.yaw(), camera.pitch());
        player.setYHeadRot(camera.yaw());
    }
}

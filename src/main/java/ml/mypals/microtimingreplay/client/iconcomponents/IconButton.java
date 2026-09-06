package ml.mypals.microtimingreplay.client.iconcomponents;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

public class IconButton extends Button {

    private final Identifier icon;
    private final int iconOffsetX;
    private final int iconOffsetY;
    private final int srcWidth;
    private final int srcHeight;
    private final int textureWidth;
    private final int textureHeight;
    private final float color;

    public IconButton(
            int x,
            int y,
            int width,
            int height,
            Component message,
            OnPress onPress,
            Identifier icon
    ) {
        super(x, y, width, height, message, onPress, Supplier::get);
        this.icon = icon;
        this.iconOffsetX = -1;
        this.iconOffsetY = -1;
        srcHeight = 16;
        srcWidth = 16;
        textureWidth = 16;
        textureHeight = 16;
        color = ARGB.white(100);
    }

    public IconButton(
            int x,
            int y,
            int width,
            int height,
            Component message,
            OnPress onPress,
            Identifier icon,
            int iconOffsetX,
            int iconOffsetY,
            float color
    ) {
        super(x, y, width, height, message, onPress, Supplier::get);
        this.icon = icon;
        this.iconOffsetX = iconOffsetX;
        this.iconOffsetY = iconOffsetY;
        this.srcHeight = 16;
        this.srcWidth = 16;
        this.textureWidth = 16;
        this.textureHeight = 16;
        this.color = color;
    }
    @Override
    protected void extractContents(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        this.extractDefaultSprite(graphics);

        int iconSize = Math.min(12, this.getHeight() - 4);
        int iconX = this.getX() + Math.max(0, this.iconOffsetX);
        int iconY = this.getY() + Math.max(0, this.iconOffsetY);

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                this.icon,
                iconX,
                iconY,
                0.0f,
                0.0f,
                iconSize,
                iconSize,
                this.srcWidth,
                this.srcHeight,
                this.textureWidth,
                this.textureHeight,
                (int) this.color
        );

        ActiveTextCollector text = graphics.textRendererForWidget(
                this,
                GuiGraphicsExtractor.HoveredTextEffects.NONE
        );

        this.extractScrollingStringOverContents(
                text,
                this.getMessage(),
                iconSize + 8
        );
    }
}

package com.faz.hubdimension.mixin;

import com.faz.hubdimension.HubConfig;
import com.faz.hubdimension.HubDimension;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    /**
     * Every ServerLevel runs its own weather cycle and tells its players about every change.
     * In the hub that would fight with the fixed weather we send (flickering rain). So in the hub the
     * vanilla weather cycle is switched off, unless the config says "follow the overworld".
     */
    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void hubdimension$ownWeather(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension() == HubDimension.HUB
                && HubConfig.WEATHER_MODE.get() != HubConfig.WeatherMode.FOLLOW_OVERWORLD) {
            ci.cancel();
        }
    }
}

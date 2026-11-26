/**
 * MaxTech energy (MLE, PRD 5.11): amounts in MaxTech Units (MU, plain {@code long}s), the {@link FeRate conversion} to
 * and from FE, and energy networks. A block owns an {@link EnergyEndpoint} (producer, consumer or buffer) and declares
 * per-tick supply or demand on it; the {@link EnergyNetwork} it is added to hands energy out once per tick, by
 * priority, with an even (linear) brownout inside a priority, and sleeps while nothing changes. Networks are formed by
 * the mod that hosts them (Storage: one per storage network; a cable mod: one per conduit graph) and run by the level's
 * {@link EnergyScheduler} ({@link EnergyNetworks#of}).
 * <p>
 * Other mods' blocks join a network as {@link EnergyEdge}s (Lib's FE edge reaches them through NeoForge's energy
 * capability), converted at the FE rate in exact {@link FeRate#unitsOfFe units} so no round trip creates energy; how
 * much may go out to them is set by {@link EdgeSettings}.
 * <p>
 * Diagnostics: {@link EnergyNetwork#readout()} gives an {@link EnergyReadout} from the last pass without waking the
 * network; blocks show their endpoint through {@link EnergyNetworks#ENDPOINT_CAPABILITY} so overlays and tools find it.
 * <p>
 * Threading: everything here runs on the server thread unless a type says otherwise. Experimental (Lib 0.10.0, edges
 * 0.11.0, diagnostics 0.12.0) until Storage's switch-over from FE proves the shape; any type may change in a Lib minor
 * version.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.energy;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

package tv.glass.launcher;

import static org.junit.Assert.assertEquals;

import io.github.jqssun.airplay.Prefs;
import io.github.jqssun.airplay.renderer.AudioConfig;
import org.junit.Test;

/** A freshly built AudioRenderer must match the saved-settings defaults;
 *  any difference rebuilt the audio output at the start of a session. */
public class AudioConfigDefaultsTest {
    @Test public void audioConfigDefaultsMatchPreferenceDefaults() {
        AudioConfig c = new AudioConfig();
        Prefs p = Prefs.INSTANCE;
        assertEquals(p.getFORCE_SW_ALAC().getDef(), c.getForceSwAlac());
        assertEquals(p.getFORCE_SW_AAC().getDef(), c.getForceSwAac());
        assertEquals(p.getKEY_PRIORITY().getDef(), c.getRealtimePriority());
        assertEquals(p.getLOW_LATENCY().getDef(), c.getLowLatency());
        assertEquals(p.getBENCHMARK_LOG().getDef(), c.getBenchmarkLog());
        assertEquals(p.getOBOE_BUFFER_FRAMES().getDef().intValue(), c.getOboeBufferFrames());
        assertEquals(p.getAUDIO_AUTO_BUFFER().getDef() ? 0 : p.getAUDIO_CUSHION_MS().getDef(), c.getCushionMs());
        assertEquals(Prefs.INSTANCE.getADAPTIVE_PERCENTILES()[p.getAUDIO_ADAPTIVE_STEP().getDef()], c.getPercentilePct());
    }
}

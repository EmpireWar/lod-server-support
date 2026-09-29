package dev.vox.lss.networking.client;

import dev.vox.lss.common.farplayers.FarPlayerWire;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class FarPlayerPreferenceDeliveryTest {
    private static FarPlayerWire.Prefs hidden() { return new FarPlayerWire.Prefs(false,0,0,false,0); }
    @Test void failedCombinedReceiveOffShareOffRetriesWithoutAnyAcquisition() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        delivery.sessionReady(connection);
        assertEquals(FarPlayerPreferenceDelivery.Outcome.FAILED,
                delivery.accept(connection,hidden(),prefs->{throw new IllegalStateException("transport unavailable");}));
        assertTrue(delivery.pending());
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        for(int i=0;i<19;i++) delivery.tick(connection,prefs->{sent.add(prefs);return FarPlayerPreferenceDelivery.Outcome.SENT;});
        assertTrue(sent.isEmpty(),"retry is bounded");
        delivery.tick(connection,prefs->{sent.add(prefs);return FarPlayerPreferenceDelivery.Outcome.SENT;});
        assertEquals(java.util.List.of(hidden()),sent);
        assertFalse(delivery.pending());
        assertEquals(FarPlayerPreferenceDelivery.Outcome.SENT,delivery.outcome());
    }
    @Test void replacementConnectionCannotReceiveOldPendingOptOut() {
        var delivery=new FarPlayerPreferenceDelivery(); Object a=new Object(),b=new Object();
        delivery.sessionReady(a);
        delivery.accept(a,hidden(),prefs->FarPlayerPreferenceDelivery.Outcome.FAILED);
        for(int i=0;i<40;i++)delivery.tick(b,prefs->{fail("old preference leaked to another server");return null;});
        assertFalse(delivery.pending());
        assertEquals(FarPlayerPreferenceDelivery.Outcome.NO_CHANNEL,delivery.outcome());
    }
    @Test void unknownChannelNeverClaimsSuccessfulDeliveryAndAnUnchangedReloadCannotErasePending() {
        var delivery=new FarPlayerPreferenceDelivery();Object connection=new Object();
        assertEquals(FarPlayerPreferenceDelivery.Outcome.NO_CHANNEL,
                delivery.accept(connection,hidden(),prefs->{fail("no valid session");return null;}));
        delivery.sessionReady(connection);
        delivery.accept(connection,hidden(),prefs->FarPlayerPreferenceDelivery.Outcome.NO_CHANNEL);
        assertTrue(delivery.pending());
        // An unchanged settings transaction performs no accept call; owner ticks retain pending.
        for(int i=0;i<20;i++)delivery.tick(connection,prefs->FarPlayerPreferenceDelivery.Outcome.SENT);
        assertEquals(FarPlayerPreferenceDelivery.Outcome.SENT,delivery.outcome());
    }
}

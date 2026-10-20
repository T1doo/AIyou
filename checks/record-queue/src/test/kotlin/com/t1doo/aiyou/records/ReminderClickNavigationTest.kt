package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.ReminderEnvelope
import com.t1doo.aiyou.ui.*
import org.junit.Assert.*
import org.junit.Test

class ReminderClickNavigationTest {
    private val envelope=ReminderEnvelope("a".repeat(64),ByteArray(29))
    private fun state(phase:AppAccessPhase,session:Long=0)=AppAccessState(phase,session=session)
    @Test fun lockedClickWaitsForCurrentAuthenticationAndCancelCannotBeReauthorizedLater(){
        val navigation=ReminderClickNavigation();navigation.replace(envelope,state(AppAccessPhase.LOCKED),3)
        assertNull(navigation.currentForRead(state(AppAccessPhase.LOCKED)))
        assertNull(navigation.currentForRead(state(AppAccessPhase.AUTHENTICATING)))
        assertNull(navigation.currentForRead(state(AppAccessPhase.LOCKED)))
        assertNull(navigation.currentForRead(state(AppAccessPhase.OPEN,1)))
        navigation.replace(envelope,state(AppAccessPhase.LOCKED),3)
        assertNull(navigation.currentForRead(state(AppAccessPhase.AUTHENTICATING)))
        assertNull(navigation.currentForRead(state(AppAccessPhase.OPENING)))
        val request=navigation.currentForRead(state(AppAccessPhase.OPEN,2))!!
        assertEquals(3,request.restoreGeneration)
        assertTrue(navigation.finish(request,2,state(AppAccessPhase.OPEN,2)))
        assertNull(navigation.currentForRead(state(AppAccessPhase.OPEN,2)))
    }
    @Test fun replacementRetirementAndNewInstanceDropOldReadResults(){
        val navigation=ReminderClickNavigation();navigation.replace(envelope,state(AppAccessPhase.OPEN,1),0)
        val old=navigation.currentForRead(state(AppAccessPhase.OPEN,1))!!
        navigation.replace(envelope,state(AppAccessPhase.OPEN,1),1)
        assertFalse(navigation.finish(old,1,state(AppAccessPhase.OPEN,1)))
        val next=navigation.currentForRead(state(AppAccessPhase.OPEN,1))!!
        assertNull(navigation.currentForRead(state(AppAccessPhase.LOCKED)))
        assertFalse(navigation.finish(next,1,state(AppAccessPhase.OPEN,1)))
        assertNull(navigation.currentForRead(state(AppAccessPhase.OPEN,2)))
        assertNull(ReminderClickNavigation().currentForRead(state(AppAccessPhase.OPEN,2)))
    }
    @Test fun routeMustBeReadyAndCannotChangeWhileAnAuthorizedReadIsInFlight(){
        val navigation=ReminderClickNavigation();val open=state(AppAccessPhase.OPEN,7)
        navigation.replace(envelope,open,0)
        assertNull(navigation.currentForRead(open,null))
        val request=navigation.currentForRead(open,3)!!
        assertFalse(navigation.finish(request,7,open,4))
        assertNull(navigation.currentForRead(open,4))
        navigation.replace(envelope,open,0)
        val fresh=navigation.currentForRead(open,4)!!
        assertTrue(navigation.finish(fresh,7,open,4))
    }

}

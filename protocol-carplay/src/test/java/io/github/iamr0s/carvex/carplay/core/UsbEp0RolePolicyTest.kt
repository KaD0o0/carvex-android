package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class UsbEp0RolePolicyTest {
    private fun setup(type:Int,request:Int,value:Int=0,index:Int=0,length:Int=0)=
        byteArrayOf(type.toByte(),request.toByte(),value.toByte(),(value ushr 8).toByte(),index.toByte(),(index ushr 8).toByte(),length.toByte(),(length ushr 8).toByte())
    private fun context(generation:Long=1,phase:UsbEp0RolePolicy.Phase=UsbEp0RolePolicy.Phase.IN_DATA)=
        UsbEp0RolePolicy.Context(generation,UsbEp0RolePolicy.Role.DEVICE,phase,true,4)
    private fun complete(policy:UsbEp0RolePolicy,token:Long,generation:Long=1,success:Boolean=true)=
        policy.completeStatus(token,generation,UsbEp0RolePolicy.Phase.STATUS,success)

    @Test fun exactQueriesReturnRecoveredBytesWithoutChangingRole(){
        val policy=UsbEp0RolePolicy(1)
        val first=policy.prepare(setup(0xc0,0x53,length=4),1,context()) as UsbEp0RolePolicy.Decision.Reply
        assertArrayEquals(byteArrayOf(1,0,0,0),first.responseBytes())
        first.responseBytes().fill(9)
        assertArrayEquals(byteArrayOf(1,0,0,0),first.responseBytes())
        assertEquals(UsbEp0RolePolicy.Completion.NoRoleRequest,complete(policy,1))
        val second=policy.prepare(setup(0xc0,0x45,length=3),2,context()) as UsbEp0RolePolicy.Decision.Reply
        assertArrayEquals(byteArrayOf(3,3,3),second.responseBytes())
        assertEquals(UsbEp0RolePolicy.Completion.NoRoleRequest,complete(policy,2))
    }

    @Test fun roleIntentIsQueuedOnlyOnceAfterSuccessfulStatus(){
        val policy=UsbEp0RolePolicy(1)
        val out=context(phase=UsbEp0RolePolicy.Phase.OUT_NO_DATA)
        assertTrue(policy.prepare(setup(0x40,0x51,1),1,out) is UsbEp0RolePolicy.Decision.Reply)
        assertEquals(UsbEp0RolePolicy.Completion.Rejected(UsbEp0RolePolicy.Error.CONTROL_PHASE_INVALID),
            policy.completeStatus(1,1,UsbEp0RolePolicy.Phase.OUT_NO_DATA,true))
        assertEquals(UsbEp0RolePolicy.Completion.RoleRequestQueued(1,1),complete(policy,1))
        assertTrue(policy.prepare(setup(0x40,0x51,0),2,out) is UsbEp0RolePolicy.Decision.Reply)
        assertEquals(UsbEp0RolePolicy.Completion.NoRoleRequest,complete(policy,2))
        assertEquals(UsbEp0RolePolicy.Decision.Stall(UsbEp0RolePolicy.Error.CONTROL_PHASE_INVALID),policy.prepare(setup(0x40,0x51,1),1,out))
    }

    @Test fun noRoleRequestForLargeValueFailedStatusOrOldGeneration(){
        val policy=UsbEp0RolePolicy(1)
        val out=context(phase=UsbEp0RolePolicy.Phase.OUT_NO_DATA)
        policy.prepare(setup(0x40,0x51,2),1,out)
        assertEquals(UsbEp0RolePolicy.Completion.NoRoleRequest,complete(policy,1))
        policy.prepare(setup(0x40,0x51,1),2,out)
        assertEquals(UsbEp0RolePolicy.Completion.NoRoleRequest,complete(policy,2,success=false))
        policy.prepare(setup(0x40,0x51,1),3,out)
        policy.advanceGeneration(2)
        assertEquals(UsbEp0RolePolicy.Completion.Rejected(UsbEp0RolePolicy.Error.STALE_GENERATION),complete(policy,3))
        assertEquals(UsbEp0RolePolicy.Decision.Stall(UsbEp0RolePolicy.Error.STALE_GENERATION),policy.prepare(setup(0x40,0x51),4,out))
        policy.prepare(setup(0x40,0x51,0),4,context(2,UsbEp0RolePolicy.Phase.OUT_NO_DATA))
        assertEquals(UsbEp0RolePolicy.Completion.RoleRequestQueued(2,0),complete(policy,4,2))
    }

    @Test fun directionsFieldsLengthsPhasesAndBudgetsAreStrict(){
        val policy=UsbEp0RolePolicy(1,1)
        val phase=UsbEp0RolePolicy.Error.CONTROL_PHASE_INVALID
        val length=UsbEp0RolePolicy.Error.CONTROL_LENGTH_MISMATCH
        val schema=UsbEp0RolePolicy.Error.SCHEMA_UNSUPPORTED
        fun rejected(bytes:ByteArray,error:UsbEp0RolePolicy.Error,ctx:UsbEp0RolePolicy.Context=context()){
            assertEquals(UsbEp0RolePolicy.Decision.Stall(error),policy.prepare(bytes,1,ctx))
        }
        rejected(ByteArray(7),length)
        rejected(setup(0x40,0x53,length=4),schema)
        rejected(setup(0xc1,0x53,length=4),schema)
        rejected(setup(0xc0,0x53,value=1,length=4),schema)
        rejected(setup(0xc0,0x53,index=1,length=4),schema)
        rejected(setup(0xc0,0x53,length=3),length)
        rejected(setup(0xc0,0x53,length=4),length,context().copy(responseCapacity=3))
        rejected(setup(0xc0,0x53,length=4),phase,context().copy(role=UsbEp0RolePolicy.Role.HOST))
        rejected(setup(0xc0,0x53,length=4),phase,context().copy(ep0Ready=false))
        rejected(setup(0x40,0x51),phase)
        val out=context(phase=UsbEp0RolePolicy.Phase.OUT_NO_DATA)
        rejected(setup(0x40,0x51,length=1),length,out)
        rejected(setup(0x40,0x51,index=1),schema,out)
        assertEquals(UsbEp0RolePolicy.Decision.Delegate,policy.prepare(setup(0x80,6,length=18),1,context()))
        assertEquals(UsbEp0RolePolicy.Decision.Delegate,policy.prepare(setup(0x21,9),1,out))
        policy.prepare(setup(0x40,0x51,2),1,out);complete(policy,1)
        assertEquals(UsbEp0RolePolicy.Decision.Stall(UsbEp0RolePolicy.Error.LOCAL_BUDGET_EXCEEDED),policy.prepare(setup(0x40,0x51,2),2,out))
    }
}

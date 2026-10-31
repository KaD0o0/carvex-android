package io.github.iamr0s.carvex.carplay.core

/** Pure USB_EP0_ROLE_V1 responder policy. It neither submits USB transfers nor changes roles. */
class UsbEp0RolePolicy(initialGeneration:Long,private val maxTransfersPerGeneration:Int=128) {
    enum class Role { DEVICE, HOST, SWITCHING }
    enum class Phase { IN_DATA, OUT_NO_DATA, STATUS }
    enum class Error { STALE_GENERATION, CONTROL_PHASE_INVALID, CONTROL_LENGTH_MISMATCH, SCHEMA_UNSUPPORTED, LOCAL_BUDGET_EXCEEDED }
    data class Context(val generation:Long,val role:Role,val phase:Phase,val ep0Ready:Boolean,val responseCapacity:Int)
    sealed class Decision {
        /** Standard/class requests belong to the platform's normal USB dispatcher. */
        object Delegate:Decision()
        data class Stall(val error:Error):Decision()
        class Reply internal constructor(val transferToken:Long,bytes:ByteArray):Decision(){
            private val bytes=bytes.copyOf()
            fun responseBytes()=bytes.copyOf()
        }
    }
    sealed class Completion {
        object NoRoleRequest:Completion()
        /** This is an intent only; the adapter still needs actual role/re-enumeration events. */
        data class RoleRequestQueued(val generation:Long,val selector:Int):Completion()
        data class Rejected(val error:Error):Completion()
    }
    private data class Pending(val token:Long,val roleSelector:Int?)
    private var generation=initialGeneration
    private val usedTokens=mutableSetOf<Long>()
    private var pending:Pending?=null
    private var roleQueued=false
    init{require(initialGeneration>=0&&maxTransfersPerGeneration>0)}

    @Synchronized fun advanceGeneration(next:Long){
        require(next>generation)
        generation=next;pending=null;usedTokens.clear();roleQueued=false
    }

    @Synchronized fun prepare(setup:ByteArray,transferToken:Long,context:Context):Decision {
        if(setup.size!=8)return Decision.Stall(Error.CONTROL_LENGTH_MISMATCH)
        val requestType=setup[0].toInt() and 255
        val type=requestType and 0x60
        if(type==0||type==0x20)return Decision.Delegate
        if(type!=0x40)return Decision.Stall(Error.SCHEMA_UNSUPPORTED)
        if(context.generation!=generation)return Decision.Stall(Error.STALE_GENERATION)
        if(context.role!=Role.DEVICE||!context.ep0Ready||pending!=null||transferToken in usedTokens)
            return Decision.Stall(Error.CONTROL_PHASE_INVALID)
        fun le16(offset:Int)=(setup[offset].toInt() and 255) or ((setup[offset+1].toInt() and 255) shl 8)
        val request=setup[1].toInt() and 255
        val value=le16(2);val index=le16(4);val length=le16(6)
        var selector:Int?=null
        val bytes=when {
            requestType==0xc0&&(request==0x53||request==0x45)->{
                if(context.phase!=Phase.IN_DATA)return Decision.Stall(Error.CONTROL_PHASE_INVALID)
                if(value!=0||index!=0)return Decision.Stall(Error.SCHEMA_UNSUPPORTED)
                val expected=if(request==0x53)4 else 3
                if(length!=expected||context.responseCapacity<expected)return Decision.Stall(Error.CONTROL_LENGTH_MISMATCH)
                if(request==0x53)byteArrayOf(1,0,0,0) else byteArrayOf(3,3,3)
            }
            requestType==0x40&&request==0x51->{
                if(context.phase!=Phase.OUT_NO_DATA)return Decision.Stall(Error.CONTROL_PHASE_INVALID)
                if(index!=0)return Decision.Stall(Error.SCHEMA_UNSUPPORTED)
                if(length!=0)return Decision.Stall(Error.CONTROL_LENGTH_MISMATCH)
                if(value<=1)selector=value
                byteArrayOf()
            }
            else->return Decision.Stall(Error.SCHEMA_UNSUPPORTED)
        }
        if(usedTokens.size>=maxTransfersPerGeneration)return Decision.Stall(Error.LOCAL_BUDGET_EXCEEDED)
        usedTokens+=transferToken;pending=Pending(transferToken,selector)
        return Decision.Reply(transferToken,bytes)
    }

    /** Invoke only from the actual control status completion, never merely after reply submission. */
    @Synchronized fun completeStatus(transferToken:Long,eventGeneration:Long,phase:Phase,successful:Boolean):Completion {
        if(eventGeneration!=generation)return Completion.Rejected(Error.STALE_GENERATION)
        val transfer=pending
        if(phase!=Phase.STATUS||transfer==null||transfer.token!=transferToken)
            return Completion.Rejected(Error.CONTROL_PHASE_INVALID)
        pending=null
        if(!successful)return Completion.NoRoleRequest
        val selector=transfer.roleSelector
        if(selector==null||roleQueued)return Completion.NoRoleRequest
        roleQueued=true
        return Completion.RoleRequestQueued(generation,selector)
    }
}

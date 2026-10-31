package io.github.iamr0s.carvex.carplay.core
/** docs/03: supported control schemas; transport or dial direction never selects the order. */
object IapStartupPolicy {
    enum class Order { AUTH_FIRST, IDENT_FIRST }
    enum class Schema(val order:Order) {
        CSM_CONTROL_V1(Order.AUTH_FIRST), CSM_CONTROL_V2(Order.IDENT_FIRST),
        LEGACY_BOOTSTRAP(Order.AUTH_FIRST)
    }

    /** Also used before a CarPlay link replies to a SYN, so unsupported versions are
     * never promised on the wire. A legacy stage must be explicitly supplied by its
     * adapter, never inferred from USB/HID. Link framing version is not an input. */
    fun selectSchema(kind:IapControlSessionKind,controlSessionVersion:Int?):Schema?=when(kind){
        IapControlSessionKind.IPOD_BOOTSTRAP->Schema.LEGACY_BOOTSTRAP
        IapControlSessionKind.CARPLAY->when(controlSessionVersion){
            1->Schema.CSM_CONTROL_V1
            2->Schema.CSM_CONTROL_V2
            else->null
        }
        IapControlSessionKind.OTHER->null
    }

    /** Retained for existing callers; both versions now have real Phone exchange evidence. */
    fun u2iwStart(kind:IapControlSessionKind,controlSessionVersion:Int?):Order?=
        selectSchema(kind,controlSessionVersion)?.order

    /** No speculative IDENT_FIRST fallback for an unknown schema. */
    fun candidateStart(kind:IapControlSessionKind,controlSessionVersion:Int?):Order=
        checkNotNull(selectSchema(kind,controlSessionVersion)){"Unsupported iAP control schema"}.order
}

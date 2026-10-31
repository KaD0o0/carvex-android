package io.github.iamr0s.carvex.carplay.core
/** Only command-body schema/input failures are contained. Transport and crypto failures propagate. */
object CommandBoundary {
    fun handle(body:ByteArray,log:(String)->Unit={},handler:(Map<String,Any?>)->Pair<Int,ByteArray>):Pair<Int,ByteArray> {
        val command=try{Plist.dictionary(body)}catch(t:RuntimeException){
            log("Malformed command plist rejected: "+t.javaClass.simpleName)
            return 400 to byteArrayOf()
        }
        return try{handler(command)}catch(t:IllegalArgumentException){
            log("Invalid command fields rejected: "+t.javaClass.simpleName)
            400 to byteArrayOf()
        }catch(t:IndexOutOfBoundsException){
            log("Invalid command layout rejected: "+t.javaClass.simpleName)
            400 to byteArrayOf()
        }
    }
}

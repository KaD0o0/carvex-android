package io.github.iamr0s.carvex.carplay.core
/** No guessed rotation/crop/view-area. Full-display scaling only when aspect ratios match. */
data class ProjectionGeometry(val receiverWidth:Int,val receiverHeight:Int,val width:Int,val height:Int) {
    init {
        require(receiverWidth>1&&receiverHeight>1&&width>1&&height>1){"Invalid projection dimensions"}
        require(receiverWidth.toLong()*height==receiverHeight.toLong()*width){
            "Host ${width}x$height and receiver ${receiverWidth}x$receiverHeight have different aspect ratios; explicit rotation/view-area mapping needed"
        }
    }
    fun map(sample:TouchSample):TouchSample=TouchSample(sample.pressed,
        sample.x.coerceIn(0f,(receiverWidth-1).toFloat())/(receiverWidth-1)*(width-1),
        sample.y.coerceIn(0f,(receiverHeight-1).toFloat())/(receiverHeight-1)*(height-1))
}

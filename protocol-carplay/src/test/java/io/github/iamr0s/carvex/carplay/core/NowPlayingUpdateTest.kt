package io.github.iamr0s.carvex.carplay.core

import org.junit.Assert.*
import org.junit.Test

class NowPlayingUpdateTest {
    private fun hex(s:String)=s.replace(" ","").chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private val subscription=hex("4040001e5000000c00000004000100040004000c00010004000000040001")
    private fun fields(packet:ByteArray)=IapWire.parseFieldList(packet.copyOfRange(6,packet.size))
    @Test fun publishedSubscriptionAndUpdatesMatchTheGoldenSequence(){
        val sent=mutableListOf<ByteArray>()
        val publisher=NowPlayingPublisher{sent.add(IapWire.csm(NowPlayingUpdate.ID,it));true}
        assertFalse(publisher.update(NowPlayingUpdate.Track(title="A",durationMs=180000,elapsedMs=0,
            status=NowPlayingUpdate.PlaybackStatus.PLAYING)))
        publisher.linkReady(true)
        assertTrue(publisher.command(0x5000,fields(subscription)))
        publisher.update(NowPlayingUpdate.Track(elapsedMs=1234))
        publisher.update(NowPlayingUpdate.Track(status=NowPlayingUpdate.PlaybackStatus.PAUSED))
        publisher.update(NowPlayingUpdate.Track(status=NowPlayingUpdate.PlaybackStatus.STOPPED))
        publisher.command(0x5002,IapFields(emptyList()))
        assertFalse(publisher.update(NowPlayingUpdate.Track(elapsedMs=2222)))
        val expected=listOf(
            "40400028500100110000000500010000080004000000000011000100050000000008000100000000",
            "40400029500100120000000600014100000800040002bf200011000100050000010008000100000000",
            "404000125001000c000100080001000004d2",
            "4040000f5001000900010005000002",
            "4040000f5001000900010005000000")
        assertEquals(5,sent.size)
        expected.forEachIndexed{i,value->assertArrayEquals(hex(value),sent[i])}
    }
    @Test fun unknownStatusAndEmptyGroupsAreOmittedAndNothingIsClamped(){
        assertTrue(NowPlayingUpdate.fields(NowPlayingUpdate.Track()).isEmpty())
        val media=NowPlayingUpdate.fields(NowPlayingUpdate.Track(title="A"))
        assertEquals(listOf(0),media.map{it.first})
        assertThrows(IllegalArgumentException::class.java){NowPlayingUpdate.encode(NowPlayingUpdate.Track())}
        for(value in listOf(-1L,0x100000000L)){
            assertThrows(IllegalArgumentException::class.java){NowPlayingUpdate.fields(NowPlayingUpdate.Track(durationMs=value))}
            assertThrows(IllegalArgumentException::class.java){NowPlayingUpdate.fields(NowPlayingUpdate.Track(elapsedMs=value))}
        }
        assertNull(NowPlayingUpdate.textOrNull("A\u0000B"))
        assertThrows(IllegalArgumentException::class.java){NowPlayingUpdate.fields(NowPlayingUpdate.Track(title="A\u0000B"))}
        assertArrayEquals(hex("404000125001000c00000008000400000000"),NowPlayingUpdate.encode(NowPlayingUpdate.Track(durationMs=0)))
    }
    @Test fun utf8TextUsesByteLengthsAndRetainsExplicitEmptyValues(){
        assertEquals("中文",NowPlayingUpdate.textOrNull("中文"))
        assertArrayEquals(hex("404000155001000f0000000b0001e4b8ade6968700"),
            NowPlayingUpdate.encode(NowPlayingUpdate.Track(title="中文")))
        assertArrayEquals(hex("404000135001000d000000090001f09f8eb500"),
            NowPlayingUpdate.encode(NowPlayingUpdate.Track(title="\uD83C\uDFB5")))
        assertArrayEquals(hex("4040000f5001000900000005000100"),
            NowPlayingUpdate.encode(NowPlayingUpdate.Track(title="")))
        assertTrue(NowPlayingUpdate.fields(NowPlayingUpdate.Track(title=null)).isEmpty())
        assertEquals("a".repeat(4096),NowPlayingUpdate.textOrNull("a".repeat(4096)))
        assertEquals("中".repeat(1365),NowPlayingUpdate.textOrNull("中".repeat(1365)))
        assertNull(NowPlayingUpdate.textOrNull("中".repeat(1366)))
        assertNull(NowPlayingUpdate.textOrNull("a".repeat(4097)))
    }
    @Test fun invalidUnicodeIsRejectedRatherThanSilentlyReplaced(){
        for(value in listOf("\uD800","\uDC00","A\uD800B","\uD800\uD800")){
            assertNull(NowPlayingUpdate.textOrNull(value))
            assertThrows(IllegalArgumentException::class.java){
                NowPlayingUpdate.fields(NowPlayingUpdate.Track(title=value))
            }
        }
    }
    @Test fun selectionReplacesAtomicallyAndDoesNotResurrectAfterLinkReset(){
        val sent=mutableListOf<ByteArray>()
        val publisher=NowPlayingPublisher{sent.add(IapWire.csm(0x5001,it));true}
        publisher.update(NowPlayingUpdate.Track(title="A",elapsedMs=12),replaceMedia=true)
        assertFalse(publisher.command(0x5000,fields(subscription)))
        publisher.linkReady(true);publisher.command(0x5000,fields(subscription))
        assertEquals(2,sent.size)
        publisher.command(0x5000,IapFields(listOf(1 to IapWire.fields(listOf(1 to byteArrayOf())))))
        val last=fields(sent.last())
        assertEquals(listOf(1),last.ordered.map{it.first})
        assertFalse(publisher.update(NowPlayingUpdate.Track(title="B"),replaceMedia=true))
        publisher.linkReady(false);publisher.linkReady(true)
        assertFalse(publisher.update(NowPlayingUpdate.Track(elapsedMs=2)))
        publisher.command(0x5000,fields(subscription))
        val newest=fields(sent.last())
        assertEquals("B",IapWire.text(IapWire.parseFieldList(newest[0]!!)[1]!!))
    }
    /** 固件语义：坏订阅整条丢弃（不改 selection、不抛异常）；5002 的多余参数被忽略。 */
    @Test fun malformedSubscriptionIsIgnoredWithoutTouchingTheSelection(){
        val publisher=NowPlayingPublisher{true};publisher.linkReady(true)
        publisher.command(0x5000,fields(subscription))
        assertTrue(publisher.update(NowPlayingUpdate.Track(title="A")))
        val invalid=IapFields(listOf(0 to IapWire.fields(listOf(1 to byteArrayOf(1)))))
        assertFalse(publisher.command(0x5000,invalid))
        assertTrue(publisher.update(NowPlayingUpdate.Track(title="B")))
        assertTrue(publisher.command(0x5002,IapFields(listOf(0 to byteArrayOf()))))
        assertFalse(publisher.update(NowPlayingUpdate.Track(title="C")))
    }

    @Test fun emptySubscriptionDoesNotEmitAnEmpty5001(){
        val sent=mutableListOf<List<Pair<Int,ByteArray>>>()
        val publisher=NowPlayingPublisher{sent.add(it);true};publisher.linkReady(true)
        assertTrue(publisher.command(0x5000,IapFields(emptyList())))
        assertTrue(sent.isEmpty())
    }

    @Test fun subscriptionCapacityMatchesFirmwareArrays(){
        val media=IapWire.fields((0 until 29).map{it to byteArrayOf()})
        val playback=IapWire.fields((0 until 19).map{it to byteArrayOf()})
        assertNull(NowPlayingUpdate.selection(IapFields(listOf(0 to media))))
        assertNull(NowPlayingUpdate.selection(IapFields(listOf(1 to playback))))
    }

    /** 第 17 章 §2.1：组 1 的 0x11 是 U32；重复组追加；未知顶层参数忽略。 */
    @Test fun groupOneStartIndexOverrideIsU32AndRepeatedGroupsOverride(){
        val group1=IapWire.fields(listOf(1 to byteArrayOf(),0x11 to IapWire.be32(7)))
        val sel=NowPlayingUpdate.selection(IapFields(listOf(
            0 to IapWire.fields(listOf(1 to byteArrayOf())),
            1 to group1,
            1 to IapWire.fields(listOf(3 to byteArrayOf())),
            9 to byteArrayOf())))
        assertNotNull(sel)
        assertEquals(listOf(NowPlayingUpdate.Attribute(1,1),NowPlayingUpdate.Attribute(1,0x11),NowPlayingUpdate.Attribute(1,3)),sel!!.attributes.filter{it.group==1})
        assertEquals(7L,sel.startIndexOverride)
        assertNull(NowPlayingUpdate.selection(IapFields(listOf(1 to IapWire.fields(listOf(0x11 to byteArrayOf(1)))))))
    }

    @Test fun repeatedSubscriptionEntriesAreRetainedFor5001Encoding(){
        val selection=NowPlayingUpdate.selection(IapFields(listOf(
            0 to IapWire.fields(listOf(1 to byteArrayOf())),
            0 to IapWire.fields(listOf(1 to byteArrayOf())))))
        assertNotNull(selection)
        assertEquals(2,selection!!.orderedAttributes.count{it==NowPlayingUpdate.Attribute(0,1)})
        val encoded=NowPlayingUpdate.fields(NowPlayingUpdate.Track(title="A"),selection)
        val children=IapWire.parseFieldList(encoded.single().second).ordered
        assertEquals(listOf(1,1),children.map{it.first})
    }

    @Test fun replacingMediaDropsStaleFieldsRatherThanInventingClearValues(){
        val sent=mutableListOf<List<Pair<Int,ByteArray>>>()
        val publisher=NowPlayingPublisher{sent.add(it);true}
        publisher.update(NowPlayingUpdate.Track(title="A",artist="Old",durationMs=100,status=NowPlayingUpdate.PlaybackStatus.PLAYING))
        publisher.update(NowPlayingUpdate.Track(title="B"),replaceMedia=true)
        publisher.linkReady(true);publisher.command(0x5000,fields(subscription))
        assertEquals(2,sent.size)
        assertEquals(listOf(0),sent.last().map{it.first})
        assertEquals(listOf(1),IapWire.parseFieldList(sent.last().single().second).ordered.map{it.first})
    }

    @Test fun firmwareSupportedExtendedAttributesEncodeWithTheirDocumentedWidths(){
        val fields=NowPlayingUpdate.fields(NowPlayingUpdate.Track(
            persistentId=7,albumTrackNum=2,genre="Rock",isLiked=true,chapterCount=3,
            queueChapterIndex=4,shuffleMode=1,repeatMode=2,playbackSpeed=100,
            setTimeElapsedAvailable=true,appName="Player"),NowPlayingUpdate.Selection(
            (listOf(0,7,0x10,0x17,0x1b).map{NowPlayingUpdate.Attribute(0,it)}+
                listOf(4,5,6,7,0xc,0xd).map{NowPlayingUpdate.Attribute(1,it)}).toSet()))
        assertEquals(listOf(0,1),fields.map{it.first})
        val media=IapWire.parseFieldList(fields.first{it.first==0}.second).ordered
        assertEquals(listOf(0,7,0x10,0x17,0x1b),media.map{it.first})
        val playback=IapWire.parseFieldList(fields.first{it.first==1}.second).ordered
        assertEquals(listOf(4,5,6,7,0xc,0xd),playback.map{it.first})
        assertEquals(1,playback.first{it.first==5}.second.size)
    }

    @Test fun queueFieldsRemainUnsigned32BitValues(){
        val encoded=NowPlayingUpdate.fields(NowPlayingUpdate.Track(queueIndex=0xffffffffL,queueCount=0x80000000L))
        val values=IapWire.parseFieldList(encoded.first{it.first==1}.second).ordered
        assertArrayEquals(IapWire.be32(0xffffffffL),values.first{it.first==2}.second)
        assertArrayEquals(IapWire.be32(0x80000000L),values.first{it.first==3}.second)
        val publisher=NowPlayingPublisher{true};publisher.linkReady(true)
        publisher.command(0x5000,IapFields(listOf(1 to IapWire.fields(listOf(2 to byteArrayOf())))))
        assertTrue(publisher.setInfo(IapFields(listOf(1 to IapWire.be32(0xffffffffL)))))
    }
}

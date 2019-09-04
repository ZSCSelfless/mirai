package net.mamoe.mirai.network.packet.login

import net.mamoe.mirai.network.packet.PacketId
import net.mamoe.mirai.network.packet.ServerPacket
import net.mamoe.mirai.network.packet.dataInputStream
import net.mamoe.mirai.network.packet.goto
import net.mamoe.mirai.util.TestedSuccessfully
import net.mamoe.mirai.utils.TEACryptor
import java.io.DataInputStream

/**
 * @author NaturalHG
 */
@PacketId("08 36 31 03")
class ServerLoginResponseResendPacket(input: DataInputStream, val flag: Flag) : ServerPacket(input) {
    enum class Flag {
        `08 36 31 03`,
        OTHER,
    }

    lateinit var _0836_tlv0006_encr: ByteArray;//120bytes
    var tokenUnknown: ByteArray? = null
    lateinit var tgtgtKey: ByteArray//16bytes

    @TestedSuccessfully
    override fun decode() {
        this.input.skip(5)
        tgtgtKey = this.input.readNBytes(16)//22
        //this.input.skip(2)//25
        this.input.goto(25)
        _0836_tlv0006_encr = this.input.readNBytes(120)

        when (flag) {
            Flag.`08 36 31 03` -> {
                tokenUnknown = this.input.goto(153).readNBytes(56)
                //println(tokenUnknown!!.toUHexString())
            }

            Flag.OTHER -> {
                //do nothing in this packet.
                //[this.token] will be set in [RobotNetworkHandler]
                //token
            }
        }
    }
}

class ServerLoginResponseResendPacketEncrypted(input: DataInputStream, private val flag: ServerLoginResponseResendPacket.Flag) : ServerPacket(input) {
    override fun decode() {

    }

    @TestedSuccessfully
    fun decrypt(tgtgtKey: ByteArray): ServerLoginResponseResendPacket {
        //this.input.skip(7)
        this.input goto 14
        var data: ByteArray = this.input.readAllBytes()
        data = TEACryptor.CRYPTOR_SHARE_KEY.decrypt(data.let { it.copyOfRange(0, it.size - 1) });
        data = TEACryptor.decrypt(data, tgtgtKey)
        return ServerLoginResponseResendPacket(data.dataInputStream(), flag)
    }
}
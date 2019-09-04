package net.mamoe.mirai.network

import net.mamoe.mirai.Robot
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.event.events.qq.FriendMessageEvent
import net.mamoe.mirai.event.events.robot.RobotLoginSucceedEvent
import net.mamoe.mirai.network.packet.*
import net.mamoe.mirai.network.packet.login.*
import net.mamoe.mirai.network.packet.message.ClientSendGroupMessagePacket
import net.mamoe.mirai.network.packet.message.ServerSendFriendMessageResponsePacket
import net.mamoe.mirai.network.packet.message.ServerSendGroupMessageResponsePacket
import net.mamoe.mirai.task.MiraiThreadPool
import net.mamoe.mirai.utils.*
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit


/**
 * A RobotNetworkHandler is used to connect with Tencent servers.
 *
 * @author Him188moe
 */
@ExperimentalUnsignedTypes
internal class RobotNetworkHandler(val robot: Robot, val number: Long, private val password: String) : Closeable {

    var socket: DatagramSocket = DatagramSocket((15314 + Math.random() * 100).toInt())

    var serverIP: String = ""
        set(value) {
            serverAddress = InetSocketAddress(value, 8000)
            field = value

            restartSocket()
        }

    private lateinit var serverAddress: InetSocketAddress
    private var closed: Boolean = false

    private lateinit var token00BA: ByteArray //这些数据全部是login用的
    private lateinit var token0825: ByteArray
    private var loginTime: Int = 0
    private lateinit var loginIP: String
    private var tgtgtKey: ByteArray? = null
    private var tlv0105: ByteArray
    private lateinit var _0828_rec_decr_key: ByteArray

    private var verificationCodeSequence: Int = 0//这两个验证码使用
    private var verificationCodeCache: ByteArray? = null//每次包只发一部分验证码来
    private var verificationCodeCacheCount: Int = 1//
    private lateinit var verificationToken: ByteArray

    private lateinit var sessionKey: ByteArray//这两个是登录成功后得到的
    private lateinit var sKey: String

    /**
     * Used to access web API(for friends list etc.)
     */
    private lateinit var cookies: String
    private var gtk: Int = 0
    private var ignoreMessage: Boolean = false

    private var loginState: LoginState? = null
        set(value) {
            field = value
            if (value != null) {
                loginHook?.invoke(value)
            }
        }

    private var loginHook: ((LoginState) -> Unit)? = null

    init {
        tlv0105 = lazyEncode {
            it.writeHex("01 05 00 30")
            it.writeHex("00 01 01 02 00 14 01 01 00 10")
            it.writeRandom(16)
            it.writeHex("00 14 01 02 00 10")
            it.writeRandom(16)
        }
    }


    @ExperimentalUnsignedTypes
    private var md5_32: ByteArray = getRandomKey(32)

    /**
     * Try to login to server
     */
    internal fun tryLogin(loginHook: ((LoginState) -> Unit)? = null) {
//"14.116.136.106",
        tryLogin()
    }

    /**
     * Try to login to server
     */
    private fun tryLogin(serverAddress: String, loginHook: ((LoginState) -> Unit)? = null) {

        touch(serverAddress, loginHook)
    }

    /**
     * Start network
     */
    private fun touch(serverAddress: String, loginHook: ((LoginState) -> Unit)? = null) {
        serverIP = serverAddress
        if (loginHook != null) {
            this.loginHook = loginHook
        }
        this.sendPacket(ClientTouchPacket(this.number, this.serverIP))
    }

    private fun restartSocket() {
        socket.close()
        socket = DatagramSocket((15314 + Math.random() * 100).toInt())
        socket.connect(this.serverAddress).runCatching { }
        val zeroByte: Byte = 0
        Thread {
            while (true) {
                val dp1 = DatagramPacket(ByteArray(2048), 2048)
                try {
                    socket.receive(dp1)
                } catch (e: Exception) {
                    if (e.message == "socket closed") {
                        if (!closed) {
                            restartSocket()
                        }
                        return@Thread
                    }
                }
                MiraiThreadPool.getInstance().submit {
                    var i = dp1.data.size - 1
                    while (dp1.data[i] == zeroByte) {
                        --i
                    }
                    try {
                        onPacketReceived(ServerPacket.ofByteArray(dp1.data.copyOfRange(0, i + 1)))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }.start()
    }

    @ExperimentalUnsignedTypes
    internal fun onPacketReceived(packet: ServerPacket) {
        packet.decode()
        MiraiLogger info "Packet received: $packet"
        if (packet is ServerEventPacket) {
            sendPacket(ClientMessageResponsePacket(this.number, packet.packetId, this.sessionKey, packet.eventIdentity))
        }
        when (packet) {
            is ServerTouchResponsePacket -> {
                if (packet.serverIP != null) {//redirection
                    serverIP = packet.serverIP!!
                    //connect(packet.serverIP!!)
                    sendPacket(ClientServerRedirectionPacket(packet.serverIP!!, number))
                } else {//password submission
                    this.loginIP = packet.loginIP
                    this.loginTime = packet.loginTime
                    this.token0825 = packet.token0825
                    this.tgtgtKey = packet.tgtgtKey
                    sendPacket(ClientPasswordSubmissionPacket(this.number, this.password, packet.loginTime, packet.loginIP, packet.tgtgtKey, packet.token0825))
                }
            }

            is ServerLoginResponseFailedPacket -> {
                this.loginState = packet.loginState
                MiraiLogger error "Login failed: " + packet.loginState.toString()
                return
            }

            is ServerLoginResponseVerificationCodeInitPacket -> {
                //[token00BA]来源之一: 验证码
                this.token00BA = packet.token00BA
                this.verificationCodeCache = packet.verifyCodePart1


                if (packet.unknownBoolean != null && packet.unknownBoolean!!) {
                    this.verificationCodeSequence = 1
                    sendPacket(ClientVerificationCodeTransmissionRequestPacket(1, this.number, this.token0825, this.verificationCodeSequence, this.token00BA))
                }

            }

            is ServerVerificationCodeRepeatPacket -> {//todo 这个名字正确么
                this.tgtgtKey = packet.tgtgtKeyUpdate
                this.token00BA = packet.token00BA
                sendPacket(ClientLoginResendPacket3105(this.number, this.password, this.loginTime, this.loginIP, this.tgtgtKey!!, this.token0825, this.token00BA))
            }

            is ServerVerificationCodeTransmissionPacket -> {
                this.verificationCodeSequence++
                this.verificationCodeCache = this.verificationCodeCache!! + packet.verificationCodePartN

                this.verificationToken = packet.verificationToken
                this.verificationCodeCacheCount++

                this.token00BA = packet.token00BA


                //todo 看易语言 count 和 sequence 是怎样变化的


                if (packet.transmissionCompleted) {
                    this.verificationCodeCache
                    TODO("验证码好了")
                } else {
                    sendPacket(ClientVerificationCodeTransmissionRequestPacket(this.verificationCodeCacheCount, this.number, this.token0825, this.verificationCodeSequence, this.token00BA))
                }
            }

            is ServerLoginResponseSuccessPacket -> {
                this._0828_rec_decr_key = packet._0828_rec_decr_key
                sendPacket(ClientSessionRequestPacket(this.number, this.serverIP, this.loginIP, this.md5_32, packet.token38, packet.token88, packet.encryptionKey, this.tlv0105))
            }

            //是ClientPasswordSubmissionPacket之后服务器回复的
            is ServerLoginResponseResendPacket -> {
                if (packet.tokenUnknown != null) {
                    //this.token00BA = packet.token00BA!!
                    //println("token00BA changed!!! to " + token00BA.toUByteArray())
                }
                if (packet.flag == ServerLoginResponseResendPacket.Flag.`08 36 31 03`) {
                    this.tgtgtKey = packet.tgtgtKey
                    sendPacket(ClientLoginResendPacket3104(
                            this.number,
                            this.password,
                            this.loginTime,
                            this.loginIP,
                            this.tgtgtKey!!,
                            this.token0825,
                            when (packet.tokenUnknown != null) {
                                true -> packet.tokenUnknown!!
                                false -> this.token00BA
                            },
                            packet._0836_tlv0006_encr
                    ))
                } else {
                    sendPacket(ClientLoginResendPacket3106(
                            this.number,
                            this.password,
                            this.loginTime,
                            this.loginIP,
                            this.tgtgtKey!!,
                            this.token0825,
                            when (packet.tokenUnknown != null) {
                                true -> packet.tokenUnknown!!
                                false -> this.token00BA
                            },
                            packet._0836_tlv0006_encr
                    ))
                }
            }


            is ServerSessionKeyResponsePacket -> {
                this.sessionKey = packet.sessionKey
                MiraiThreadPool.getInstance().scheduleWithFixedDelay({
                    sendPacket(ClientHeartbeatPacket(this.number, this.sessionKey))
                }, 90000, 90000, TimeUnit.MILLISECONDS)
                RobotLoginSucceedEvent(robot).broadcast()

                MiraiThreadPool.getInstance().schedule({
                    ignoreMessage = false
                }, 2, TimeUnit.SECONDS)

                this.tlv0105 = packet.tlv0105
                sendPacket(ClientLoginStatusPacket(this.number, this.sessionKey, ClientLoginStatus.ONLINE))
            }

            is ServerLoginSuccessPacket -> {
                loginState = LoginState.SUCCEED
                sendPacket(ClientSKeyRequestPacket(this.number, this.sessionKey))
            }

            is ServerSKeyResponsePacket -> {
                this.sKey = packet.sKey
                this.cookies = "uin=o" + this.number + ";skey=" + this.sKey + ";"

                MiraiThreadPool.getInstance().scheduleWithFixedDelay({
                    sendPacket(ClientSKeyRefreshmentRequestPacket(this.number, this.sessionKey))
                }, 1800000, 1800000, TimeUnit.MILLISECONDS)

                this.gtk = getGTK(sKey)
                sendPacket(ClientAccountInfoRequestPacket(this.number, this.sessionKey))
            }

            is ServerHeartbeatResponsePacket -> {

            }

            is ServerAccountInfoResponsePacket -> {

            }


            is ServerFriendMessageEventPacket -> {
                if (ignoreMessage) {
                    return
                }

                FriendMessageEvent(this.robot, this.robot.getQQ(packet.qq), packet.message)
            }

            is ServerGroupMessageEventPacket -> {
                //group message
                if (packet.message == "牛逼") {
                    sendPacket(ClientSendGroupMessagePacket(Group.groupNumberToId(packet.groupNumber), this.number, this.sessionKey, "牛逼!"))
                }

                //todo
                //GroupMessageEvent(this.robot, this.robot.getGroup(packet.groupNumber), this.robot.getQQ(packet.qq), packet.message)
            }

            is UnknownServerEventPacket -> {
                //unknown message event
            }

            is UnknownServerPacket -> {

            }

            is ServerGroupUploadFileEventPacket -> {

            }

            is ServerMessageEventPacketRaw -> onPacketReceived(packet.analyze())

            is ServerVerificationCodePacketEncrypted -> onPacketReceived(packet.decrypt())
            is ServerLoginResponseVerificationCodePacketEncrypted -> onPacketReceived(packet.decrypt())
            is ServerLoginResponseResendPacketEncrypted -> onPacketReceived(packet.decrypt(this.tgtgtKey!!))
            is ServerLoginResponseSuccessPacketEncrypted -> onPacketReceived(packet.decrypt(this.tgtgtKey!!))
            is ServerSessionKeyResponsePacketEncrypted -> onPacketReceived(packet.decrypt(this._0828_rec_decr_key))
            is ServerTouchResponsePacketEncrypted -> onPacketReceived(packet.decrypt())
            is ServerSKeyResponsePacketEncrypted -> onPacketReceived(packet.decrypt(this.sessionKey))
            is ServerAccountInfoResponsePacketEncrypted -> onPacketReceived(packet.decrypt(this.sessionKey))
            is ServerMessageEventPacketRawEncoded -> onPacketReceived(packet.decrypt(this.sessionKey))


            is ServerSendFriendMessageResponsePacket,
            is ServerSendGroupMessageResponsePacket -> {

            }

            else -> throw IllegalArgumentException(packet.toString())
        }

    }

    @ExperimentalUnsignedTypes
    fun sendPacket(packet: ClientPacket) {
        MiraiThreadPool.getInstance().submit {
            try {
                packet.encode()
                packet.writeHex(Protocol.tail)

                val data = packet.toByteArray()
                socket.send(DatagramPacket(data, data.size))
                MiraiLogger info "Packet sent: $packet"
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun close() {
        this.socket.close()
        this.loginState = null
        this.loginHook = null
        this.verificationCodeCache = null
        this.tgtgtKey = null
    }
}

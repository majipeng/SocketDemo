package com.majipeng.wechat.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.majipeng.wechat.handler.ChannelInit;
import com.majipeng.wechat.utils.SocketState;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import majipeng.utils.RequestUtils;

public abstract class MessengerService extends Service implements DispatchMessageListener {
    public static final String TAG = "MessengerService";


    public static final int EVENT_HANDLER_INIT = 0x000001;

    /**
     * 注册一个UI(Messenger)到Service,服务器将会在收到相关Event后发送给你
     */
    public static final int EVENT_REGISTER_OB = 0x000002;
    /**
     * 解除注册一个OB,每次解除绑定Service之前,必须要先解除绑定OB
     */
    public static final int EVENT_UNREGISTER_OB = 0x000003;


    public static final int EVENT_SEND_TO_SOCKET = 0x000004;
    /**
     * socket连接状态发生变化
     */
    public static final int EVENT_SOCKET_DISCONNECTED = 0x000005;


    public static final int EVENT_SOCKET_CONNECTED = 0x000006;

    public static final int EVENT_CHANNEL_READ = 0x000007;

    public static final int EVENT_LAST = EVENT_CHANNEL_READ;

    public static final String FROM_UI = "Client";

    public static final String FROM_PROCESS = "Process";

    public static final String FROM_SERVICE = "Service";

    public static final String FROM_FILTER = "Filter";

    CountDownLatch initLock = new CountDownLatch(3);

    private Handler forUIHandler, serviceMainHandler, forProcessHandler;

    /**
     * 订阅者,注册后放在这个集合里
     */
    private ArrayList<Messenger> observers = new ArrayList<>();


    private Messenger forProcess, forUI, mCurrentUI, forFilter;

    //当前连接
    private ChannelHandlerContext mConnectContext;

    private SocketState socketState;

    public MessengerService() {
        Log.d(TAG, "new MessengerService");
        createHandlerAndMessenger();
    }

    /**
     * 分发message并拦截一些,例如OB的初始化Message
     *
     * @param from    该事件来自哪里,
     * @param message 事件
     */
    @Override
    public void dispatchMessage(String from, android.os.Message message) {
        if (handleByWhat(message)) {
            return;
        }
        switch (from) {
            case FROM_UI://来自UI的初始化OB任务,拦截,该case为service保留
                if (message.what == EVENT_REGISTER_OB) {
                    mCurrentUI = message.replyTo;
                    observers.add(message.replyTo);
                    break;
                } else if (message.what == EVENT_UNREGISTER_OB) {
                    mCurrentUI = null;
                    observers.remove(message.replyTo);
                    break;
                }
                handleFromUIMessage(message);
                break;
            case FROM_PROCESS:
                handleFromProcessMessage(message);
                break;
            case FROM_FILTER:
                if (message.what == EVENT_SOCKET_CONNECTED) {
                    socketState = SocketState.CONNECTED;
                    mConnectContext = (ChannelHandlerContext) message.obj;
                    Log.d(TAG, "Socket Connected");
                } else if (message.what == EVENT_SOCKET_DISCONNECTED) {//socket状态变化
                    Log.d(TAG, "Socket DisConnected");
                } else {
                    handleFromFilterMessage(message);
                }
                break;
            default:
                handleMessage(message);
                break;
        }
    }

    private boolean handleByWhat(Message message) {
        return false;
    }

    /**
     * from activity
     * @param msg
     */
    public abstract void handleFromUIMessage(Message msg);

    /**
     * from
     * @param msg
     */
    public abstract void handleFromProcessMessage(Message msg);

    /**
     *
     * @param msg
     */
    public abstract void handleFromFilterMessage(Message msg);

    /**
     * 运行在主线程,由主线程handler调用
     *
     * @param msg
     */
    public abstract void handleMessage(Message msg);


    //初始化Handler和Messenger
    private void createHandlerAndMessenger() {
        serviceMainHandler = new CallbackHandler(this, FROM_SERVICE);
        new Thread(new CreateForUI(),FROM_UI).start();
        new Thread(new CreateForProcess(), FROM_PROCESS).start();
        new Thread(new CreateForFilter(), FROM_FILTER).start();
    }


    //开始连接
    class StartConnect implements Runnable {
        @Override
        public void run() {
            EventLoopGroup worker = new NioEventLoopGroup();
            Bootstrap socket = new Bootstrap();
            socket.group(worker).option(ChannelOption.SO_KEEPALIVE, true)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInit(forFilter));
            try {
                ChannelFuture f = socket.connect("127.0.0.1",9999).sync();
                f.channel().closeFuture().sync();
                Log.d(TAG, "connection Closed");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }finally {
                worker.shutdownGracefully();
            }
        }
    }

    /**
     * 创建Messenger,服务于处理器
     */
    class CreateForProcess implements Runnable {
        @Override
        public void run() {
            Looper.prepare();
            forProcess = new Messenger(new CallbackHandler(MessengerService.this, FROM_PROCESS));
            initLock.countDown();
            Looper.loop();
        }
    }

    /**
     * 创建Messenger,服务于处理器
     */
    class CreateForFilter implements Runnable {
        @Override
        public void run() {
            Looper.prepare();
            forFilter = new Messenger(new CallbackHandler(MessengerService.this, FROM_FILTER));
            initLock.countDown();
            Looper.loop();
        }
    }

    /**
     * 创建Messenger,服务于UI
     */
    class CreateForUI implements Runnable {
        @Override
        public void run() {
            Looper.prepare();
            forUI = new Messenger(new CallbackHandler(MessengerService.this, FROM_UI));
            initLock.countDown();
            Looper.loop();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        new Thread(new StartConnect()).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        try {
            initLock.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return forUI.getBinder();
    }


    void sendToUI(Message msg) {
        if (mCurrentUI == null) return;
        try {
            mCurrentUI.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    //提交的message将在主线程重新执行
    void sendToSelf(Message msg) {
        serviceMainHandler.sendMessage(msg);
    }


    void sendPostRequest(String path, String content, String mimeType) throws URISyntaxException {
        //默认只需要提供一个内容类型,以及目标
        RequestUtils.send(mConnectContext,path,content,mimeType);
    }
    //service主线程Handler
}

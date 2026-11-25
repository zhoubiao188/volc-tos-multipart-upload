package com.example.volctos;

import com.example.volctos.tos.TosMockServer;
import com.example.volctos.web.BizServer;

import java.nio.file.Path;

/**
 * 一个进程里起两个服务：
 *   9090 = 对象存储 TOS（mock，带验签 + 单连接限速）
 *   8080 = 业务应用（relay / ticket / complete / parts）
 *
 * 分成两个端口是为了让 bench 真的走 HTTP，而不是同进程内方法调用"白给"一个 0ms。
 */
public class App {

    public static void main(String[] args) throws Exception {
        int tosPort = Integer.parseInt(System.getProperty("tosPort", "9090"));
        int appPort = Integer.parseInt(System.getProperty("appPort", "8080"));
        long rate = Long.parseLong(System.getProperty("tosRate", String.valueOf(20L * 1024 * 1024)));
        Path dataDir = Path.of(System.getProperty("dataDir", "data"));

        TosMockServer tos = new TosMockServer(tosPort, dataDir, rate);
        tos.start();

        BizServer app = new BizServer(appPort, "127.0.0.1:" + tosPort, "volc-demo", tos);
        app.start();

        System.out.printf("[app] TOS mock  http://127.0.0.1:%d  (单连接上限 %.0f MB/s, dataDir=%s)%n",
                tosPort, rate / 1048576.0, dataDir.toAbsolutePath());
        System.out.printf("[app] biz      http://127.0.0.1:%d  (-Xmx %d MB)%n",
                appPort, Runtime.getRuntime().maxMemory() / 1048576);
    }
}

package net.wurstclient.altmanager.screens;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.util.Session;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.wurstclient.WurstClient;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.LoginException;
import netscape.javascript.JSObject;
import org.apache.http.HttpHeaders;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MSLoginScreen extends Screen {

    private static final String CLIENT_ID = "19e476fa-1247-4c56-a287-f6b300ece124";

    private static class LoginThread extends Thread {

        private final MSLoginScreen loginScreen;
        private ServerSocket server;

        public LoginThread(MSLoginScreen loginScreen) {
            super("MS Login");
            this.loginScreen = loginScreen;
        }

        @Override
        public void run() {
            Socket socket = null;
            try {
                // login in browser
                String loginURL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?" + "client_id=" + CLIENT_ID + "&response_type=code" + "&redirect_uri=http://localhost:58281" + "&scope=XboxLive.signin" + "&response_mode=query";
                System.out.println("loginURL = " + loginURL);
                Util.getOperatingSystem().open(new URL(loginURL));
                loginScreen.setMessage("Waiting for callback...");

                // callback server
                server = new ServerSocket(58281);
                socket = server.accept();

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream());

                String line = reader.readLine();
                System.out.println("line = " + line);

                Pattern pattern = Pattern.compile("code=([A-Za-z0-9._\\-]+) ");
                Matcher matcher = pattern.matcher(line);
                if (!matcher.find()) {
                    throw new LoginException("code not found: " + line);
                }
                String code = matcher.group(1);

                String msg = "You can close this page now.";
                writer.print("HTTP/1.1 200 OK\r\n");
                writer.print("Content-Type: text/plain\r\n");
                writer.printf("Content-Length: %d\r\n", msg.length());
                writer.print("\r\n");
                writer.print(msg);
                writer.flush();

                socket.close();
                server.close();

                CloseableHttpClient httpClient = HttpClients.createDefault();

                // Get access token
                HttpPost accessTokenReq = new HttpPost("https://login.microsoftonline.com/consumers/oauth2/v2.0/token");
                accessTokenReq.addHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
                accessTokenReq.addHeader(HttpHeaders.ACCEPT, "application/json");
                ArrayList<NameValuePair> nameValuePairs = new ArrayList<>();
                nameValuePairs.add(new BasicNameValuePair("client_id", CLIENT_ID));
                nameValuePairs.add(new BasicNameValuePair("scope", "XboxLive.signin"));
                nameValuePairs.add(new BasicNameValuePair("code", code));
                nameValuePairs.add(new BasicNameValuePair("redirect_uri", "http://localhost:58281"));
                nameValuePairs.add(new BasicNameValuePair("grant_type", "authorization_code"));
                accessTokenReq.setEntity(new UrlEncodedFormEntity(nameValuePairs));

                CloseableHttpResponse accessTokenResp = httpClient.execute(accessTokenReq);
                String respString = EntityUtils.toString(accessTokenResp.getEntity());
                System.out.println("access token resp = " + respString);
                accessTokenResp.close();

                if (accessTokenResp.getStatusLine().getStatusCode() != 200) {
                    throw new LoginException("access token error: " + accessTokenResp.getStatusLine().getStatusCode());
                }

                JsonObject jsonObj = new Gson().fromJson(respString, JsonObject.class);
                String accessToken = jsonObj.get("access_token").getAsString();
                System.out.println("access token = " + accessToken);

                // Authenticate with Xbox Live
                loginScreen.setMessage("Authenticating with Xbox Live...");
                String body = "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + accessToken + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}";
                System.out.println("xbox live body = " + body);

                HttpPost xboxLive = new HttpPost("https://user.auth.xboxlive.com/user/authenticate");
                xboxLive.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                xboxLive.addHeader(HttpHeaders.ACCEPT, "application/json");
                xboxLive.setEntity(new StringEntity(body));

                CloseableHttpResponse response = httpClient.execute(xboxLive);
                String resp = EntityUtils.toString(response.getEntity());
                System.out.println("xbox live auth resp = " + resp);
                response.close();

                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new LoginException("Xbox Live auth error: " + statusCode);
                }

                JsonObject xboxObj = new Gson().fromJson(resp, JsonObject.class);
                String xboxToken = xboxObj.get("Token").getAsString();
                String uhsToken = xboxObj.get("DisplayClaims").getAsJsonObject().get("xui").getAsJsonArray().get(0).getAsJsonObject().get("uhs").getAsString();

                System.out.println("xbox token = " + xboxToken);
                System.out.println("uhs token = " + uhsToken);

                // Obtain XSTS token for Minecraft
                loginScreen.setMessage("Obtaining XSTS token for Minecraft...");
                String xstsPostStr = "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xboxToken + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}";
                System.out.println("xsts req = " + xstsPostStr);
                HttpPost xstsPost = new HttpPost("https://xsts.auth.xboxlive.com/xsts/authorize");
                xstsPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                xstsPost.addHeader(HttpHeaders.ACCEPT, "application/json");
                xstsPost.setEntity(new StringEntity(xstsPostStr));

                CloseableHttpResponse xstsResp = httpClient.execute(xstsPost);
                String xstsRespStr = EntityUtils.toString(xstsResp.getEntity());
                System.out.println("xsts resp = " + xstsRespStr);
                xstsResp.close();

                if (xstsResp.getStatusLine().getStatusCode() != 200) {
                    throw new LoginException("XSTS error: " + xstsResp.getStatusLine().getStatusCode());
                }

                JsonObject xstsObj = new Gson().fromJson(xstsRespStr, JsonObject.class);
                String xstsToken = xstsObj.get("Token").getAsString();
                System.out.println("xsts token = " + xstsToken);

                // Authenticate with Minecraft
                loginScreen.setMessage("Authenticating with Minecraft...");
                String mcReqStr = "{\"identityToken\":\"XBL3.0 x=" + uhsToken + ";" + xstsToken + "\"}";
                System.out.println("minecraft req = " + mcReqStr);
                HttpPost mcReq = new HttpPost("https://api.minecraftservices.com/authentication/login_with_xbox");
                mcReq.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
                mcReq.addHeader(HttpHeaders.ACCEPT, "application/json");
                mcReq.setEntity(new StringEntity(mcReqStr));

                CloseableHttpResponse mcResp = httpClient.execute(mcReq);
                String mcRespStr = EntityUtils.toString(mcResp.getEntity());
                System.out.println("minecraft resp = " + mcRespStr);
                mcResp.close();

                if (mcResp.getStatusLine().getStatusCode() != 200) {
                    throw new LoginException("minecraft error: " + mcResp.getStatusLine().getStatusCode());
                }

                JsonObject minecraftObj = new Gson().fromJson(mcRespStr, JsonObject.class);
                String minecraftAccessToken = minecraftObj.get("access_token").getAsString();
                System.out.println("minecraft access token = " + minecraftAccessToken);

                // Get minecraft profile
                loginScreen.setMessage("Obtaining minecraft profile...");
                HttpGet profileReq = new HttpGet("https://api.minecraftservices.com/minecraft/profile");
                profileReq.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + minecraftAccessToken);
                profileReq.addHeader(HttpHeaders.ACCEPT, "application/json");

                CloseableHttpResponse profileResp = httpClient.execute(profileReq);
                String profileStr = EntityUtils.toString(profileResp.getEntity());
                System.out.println("minecraft profile = " + profileStr);
                profileResp.close();

                JsonObject profileObj = new Gson().fromJson(profileStr, JsonObject.class);
                if (profileObj.has("error")) {
                    throw new LoginException("minecraft profile error: " + profileObj.get("error").getAsString());
                }

                String minecraftUUID = profileObj.get("id").getAsString();
                String minecraftName = profileObj.get("name").getAsString();

                Session session = new Session(minecraftName, minecraftUUID, minecraftAccessToken,
                        Optional.empty(), Optional.empty(), Session.AccountType.MOJANG);
                WurstClient.IMC.setSession(session);

                loginScreen.setMessage("Logged in as " + minecraftName);

                httpClient.close();
            } catch (Exception e) {
                e.printStackTrace();
                loginScreen.setMessage("Error: " + e.getMessage());
            } finally {
                if (server != null) try {
                    server.close();
                } catch (IOException ignored) {
                }
                if (socket != null) try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        public void close() {
            if (server != null && !server.isClosed()) {
                try {
                    server.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private final Screen prevScreen;
    private LoginThread loginThread;
    private String message = "";

    protected MSLoginScreen(Screen prevScreen, AltManager altManager) {
        super(Text.literal("Microsoft Login"));
        this.prevScreen = prevScreen;
    }

    @Override
    protected void init() {
        super.init();

        if (loginThread != null && loginThread.isAlive()) {
            loginThread.close();
            loginThread.interrupt();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        loginThread = new LoginThread(this);
        loginThread.start();

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> {
            client.setScreen(prevScreen);
            this.close();
        }).dimensions(width / 2 - 100, height / 4 * 3, 200, 20).build());
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        super.render(matrices, mouseX, mouseY, delta);

        client.textRenderer.drawWithShadow(matrices, message, width / 2f - (client.textRenderer.getWidth(message) / 2f), height / 2f, Color.WHITE.getRGB());
    }

    @Override
    public void close() {
        client.setScreen(prevScreen);
        if (loginThread != null && loginThread.isAlive()) {
            loginThread.close();
            loginThread.interrupt();
        }
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        if (loginThread != null && loginThread.isAlive()) {
            loginThread.close();
            loginThread.interrupt();
        }
        super.resize(client, width, height);
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

package org.drinkless.tdlib.example;

import com.alibaba.fastjson.JSONObject;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.event.ChangeEvent;
import java.io.IOError;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Example class for TDLib usage from Java.
 */
public final class Example {
    private static final Logger logger = LoggerFactory.getLogger(Example.class);
    private static Client client = null;

    private static TdApi.AuthorizationState authorizationState = null;
    private static volatile boolean haveAuthorization = false;
    private static volatile boolean quiting = false;

    private static final Client.ResultHandler defaultHandler = new DefaultHandler();

    private static final Lock authorizationLock = new ReentrantLock();
    private static final Condition gotAuthorization = authorizationLock.newCondition();

    private static TdApi.User me = null;

    private static final ConcurrentMap<Integer, TdApi.User> users = new ConcurrentHashMap<Integer, TdApi.User>();
    private static final ConcurrentMap<Integer, TdApi.BasicGroup> basicGroups = new ConcurrentHashMap<Integer, TdApi.BasicGroup>();
    private static final ConcurrentMap<Integer, TdApi.Supergroup> supergroups = new ConcurrentHashMap<Integer, TdApi.Supergroup>();
    private static final ConcurrentMap<Integer, TdApi.SecretChat> secretChats = new ConcurrentHashMap<Integer, TdApi.SecretChat>();

    private static final ConcurrentMap<Long, TdApi.Chat> chats = new ConcurrentHashMap<Long, TdApi.Chat>();
    private static final NavigableSet<OrderedChat> mainChatList = new TreeSet<OrderedChat>();
    private static boolean haveFullMainChatList = false;

    private static final ConcurrentMap<Integer, TdApi.UserFullInfo> usersFullInfo = new ConcurrentHashMap<Integer, TdApi.UserFullInfo>();
    private static final ConcurrentMap<Integer, TdApi.BasicGroupFullInfo> basicGroupsFullInfo = new ConcurrentHashMap<Integer, TdApi.BasicGroupFullInfo>();
    private static final ConcurrentMap<Integer, TdApi.SupergroupFullInfo> supergroupsFullInfo = new ConcurrentHashMap<Integer, TdApi.SupergroupFullInfo>();

    private static final String newLine = System.getProperty("line.separator");
    private static final String adminChatId = System.getProperty("admin.chat.id");
    private static final String commandsLine = "Enter command (gcs - GetChats, gc <chatId> - GetChat, me - GetMe, sm <chatId> <message> - SendMessage, lo - LogOut, q - Quit): \n";
    private static volatile String currentPrompt = null;

    private static final int bootUnixTime= (int) (System.currentTimeMillis() / 1000L);

    static {
        try {
            System.loadLibrary("tdjni");
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        }
    }

    private static void print(String str) {
        if (currentPrompt != null) {
            System.out.println("");
        }
        System.out.println(str);
        if (currentPrompt != null) {
            System.out.print(currentPrompt);
        }
    }

    private static void setChatOrder(TdApi.Chat chat, long order) {
        synchronized (mainChatList) {
            synchronized (chat) {
                if (chat.chatList == null || chat.chatList.getConstructor() != TdApi.ChatListMain.CONSTRUCTOR) {
                    return;
                }

                if (chat.order != 0) {
                    boolean isRemoved = mainChatList.remove(new OrderedChat(chat.order, chat.id));
                    assert isRemoved;
                }

                chat.order = order;

                if (chat.order != 0) {
                    boolean isAdded = mainChatList.add(new OrderedChat(chat.order, chat.id));
                    assert isAdded;
                }
            }
        }
    }

    private static void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        if (authorizationState != null) {
            Example.authorizationState = authorizationState;
        }
        switch (Example.authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
                parameters.databaseDirectory = "tdlib";
                parameters.useMessageDatabase = true;
                parameters.useSecretChats = true;
                parameters.apiId = 94575;
                parameters.apiHash = "a3406de8d171bb422bb6ddf3bbd800e2";
                parameters.systemLanguageCode = "en";
                parameters.deviceModel = "Desktop";
                parameters.systemVersion = "Unknown";
                parameters.applicationVersion = "1.0";
                parameters.enableStorageOptimizer = true;

                client.send(new TdApi.SetTdlibParameters(parameters), new AuthorizationRequestHandler());
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                client.send(new TdApi.CheckDatabaseEncryptionKey(), new AuthorizationRequestHandler());
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                String phoneNumber = promptString("Please enter phone number: ");
                if (phoneNumber.length() < 20) {//长度小于20认为是手机号
                    client.send(new TdApi.SetAuthenticationPhoneNumber(phoneNumber, null), new AuthorizationRequestHandler());
                } else {//大于20认为是bot
                    client.send(new TdApi.CheckAuthenticationBotToken(phoneNumber), new AuthorizationRequestHandler());
                }
                break;
            }
            case TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR: {
                String link = ((TdApi.AuthorizationStateWaitOtherDeviceConfirmation) Example.authorizationState).link;
                System.out.println("Please confirm this login link on another device: " + link);
                break;
            }
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: {
                String code = promptString("Please enter authentication code: ");
                client.send(new TdApi.CheckAuthenticationCode(code), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR: {
                String firstName = promptString("Please enter your first name: ");
                String lastName = promptString("Please enter your last name: ");
                client.send(new TdApi.RegisterUser(firstName, lastName), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                String password = promptString("Please enter password: ");
                client.send(new TdApi.CheckAuthenticationPassword(password), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                haveAuthorization = true;
                authorizationLock.lock();
                try {
                    gotAuthorization.signal();
                } finally {
                    authorizationLock.unlock();
                }
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                haveAuthorization = false;
                print("Logging out");
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                haveAuthorization = false;
                print("Closing");
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                print("Closed");
                if (!quiting) {
                    client = Client.create(new UpdatesHandler(), null, null); // recreate client after previous has closed
                }
                break;
            default:
                System.err.println("Unsupported authorization state:" + newLine + Example.authorizationState);
        }
    }

    private static int toInt(String arg) {
        int result = 0;
        try {
            result = Integer.parseInt(arg);
        } catch (NumberFormatException ignored) {
        }
        return result;
    }

    private static long getChatId(String arg) {
        long chatId = 0;
        try {
            chatId = Long.parseLong(arg);
        } catch (NumberFormatException ignored) {
        }
        return chatId;
    }

    private static String promptString(String prompt) {
        System.out.print(prompt);
        currentPrompt = prompt;
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String str = "";
        try {
            str = reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
        currentPrompt = null;
        return str;
    }

    private static void getCommand() {
        String command = promptString(commandsLine);
        String[] commands = command.split(" ", 2);
        try {
            switch (commands[0]) {
                case "gcs": {
                    int limit = 20;
                    if (commands.length > 1) {
                        limit = toInt(commands[1]);
                    }
                    getMainChatList(limit);
                    break;
                }
                case "gc":
                    client.send(new TdApi.GetChat(getChatId(commands[1])), defaultHandler);
                    break;
                case "me":
                    client.send(new TdApi.GetMe(), defaultHandler);
                    break;
                case "sm": {
                    String[] args = commands[1].split(" ", 2);
                    sendMessage(getChatId(args[0]), args[1]);
                    break;
                }
                case "lo":
                    haveAuthorization = false;
                    client.send(new TdApi.LogOut(), defaultHandler);
                    break;
                case "q":
                    quiting = true;
                    haveAuthorization = false;
                    client.send(new TdApi.Close(), defaultHandler);
                    break;
                default:
                    System.err.println("Unsupported command: " + command);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            print("Not enough arguments");
        }
    }

    private static void getMainChatList(final int limit) {
        synchronized (mainChatList) {
            if (!haveFullMainChatList && limit > mainChatList.size()) {
                // have enough chats in the chat list or chat list is too small
                long offsetOrder = Long.MAX_VALUE;
                long offsetChatId = 0;
                if (!mainChatList.isEmpty()) {
                    OrderedChat last = mainChatList.last();
                    offsetOrder = last.order;
                    offsetChatId = last.chatId;
                }
                client.send(new TdApi.GetChats(new TdApi.ChatListMain(), offsetOrder, offsetChatId, limit - mainChatList.size()), new Client.ResultHandler() {
                    @Override
                    public void onResult(TdApi.Object object) {
                        switch (object.getConstructor()) {
                            case TdApi.Error.CONSTRUCTOR:
                                System.err.println("Receive an error for GetChats:" + newLine + object);
                                break;
                            case TdApi.Chats.CONSTRUCTOR:
                                long[] chatIds = ((TdApi.Chats) object).chatIds;
                                if (chatIds.length == 0) {
                                    synchronized (mainChatList) {
                                        haveFullMainChatList = true;
                                    }
                                }
                                // chats had already been received through updates, let's retry request
                                getMainChatList(limit);
                                break;
                            default:
                                System.err.println("Receive wrong response from TDLib:" + newLine + object);
                        }
                    }
                });
                return;
            }

            // have enough chats in the chat list to answer request
            java.util.Iterator<OrderedChat> iter = mainChatList.iterator();
            System.out.println();
            System.out.println("First " + limit + " chat(s) out of " + mainChatList.size() + " known chat(s):");
            for (int i = 0; i < limit; i++) {
                long chatId = iter.next().chatId;
                TdApi.Chat chat = chats.get(chatId);
                synchronized (chat) {
                    System.out.println(chatId + ": " + chat.title);
                }
            }
            print("");
        }
    }

    private static void sendMessage(long chatId, String message) {
        // initialize reply markup just for testing
        TdApi.InlineKeyboardButton[] row = {new TdApi.InlineKeyboardButton("https://telegram.org?1", new TdApi.InlineKeyboardButtonTypeUrl("https://telegram.org?1")), new TdApi.InlineKeyboardButton("https://telegram.org?2", new TdApi.InlineKeyboardButtonTypeUrl("https://telegram.org?1")), new TdApi.InlineKeyboardButton("https://telegram.org?3", new TdApi.InlineKeyboardButtonTypeUrl("https://telegram.org?1"))};
        TdApi.ReplyMarkup replyMarkup = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][]{row, row, row});

        TdApi.InputMessageContent content = new TdApi.InputMessageText(new TdApi.FormattedText(message, null), false, true);
        client.send(new TdApi.SendMessage(chatId, 0, null, replyMarkup, content), defaultHandler);
    }

    /**
     * 用户成功登录时的操作
     *
     * @param client
     */
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void onLogin(Client client) {
        client.send(new TdApi.GetMe(), (cell) -> {
            if (cell instanceof TdApi.User) {
                me = (TdApi.User) cell;
            }
        });
    }

    public static void main(String[] args) throws InterruptedException {

        // disable TDLib log
        Client.execute(new TdApi.SetLogVerbosityLevel(0));
        if (Client.execute(new TdApi.SetLogStream(new TdApi.LogStreamFile("tdlib.log", 1 << 27))) instanceof TdApi.Error) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }

        // create client
        client = Client.create(new UpdatesHandler(), null, null);

        // test Client.execute
        //defaultHandler.onResult(Client.execute(new TdApi.GetTextEntities("@telegram /test_command https://telegram.org telegram.me @gif @test")));

        // main loop
        while (!quiting) {
            // await authorization
            authorizationLock.lock();
            try {
                while (!haveAuthorization) {
                    gotAuthorization.await();
                }
            } finally {
                authorizationLock.unlock();
            }

            onLogin(client);

            while (haveAuthorization) {
                getCommand();
            }
        }
    }

    private static class OrderedChat implements Comparable<OrderedChat> {
        final long order;
        final long chatId;

        OrderedChat(long order, long chatId) {
            this.order = order;
            this.chatId = chatId;
        }

        @Override
        public int compareTo(OrderedChat o) {
            if (this.order != o.order) {
                return o.order < this.order ? -1 : 1;
            }
            if (this.chatId != o.chatId) {
                return o.chatId < this.chatId ? -1 : 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            OrderedChat o = (OrderedChat) obj;
            return this.order == o.order && this.chatId == o.chatId;
        }
    }

    private static class DefaultHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            print(object.toString());

            if (object instanceof TdApi.Chat) {
                TdApi.Chat chat = (TdApi.Chat) object;
                chats.put(chat.id, chat);
            }
            if (object instanceof TdApi.User) {
                TdApi.User user = (TdApi.User) object;
                users.put(user.id, user);
            }
        }
    }

    private static TdApi.User getOrQueryUser(int userID) {
        if (userID == 0) {
            return null;
        }
        TdApi.User sendUser = users.get(userID);
        if (Objects.nonNull(sendUser)) {
            return sendUser;
        } else {
            client.send(new TdApi.GetUser(userID), defaultHandler);
            return null;
        }
    }

    private static TdApi.Chat getOrQueryChat(long chatId) {
        TdApi.Chat targetChat = chats.get(chatId);
        if (Objects.isNull(targetChat)) {
            client.send(new TdApi.GetChat(chatId), defaultHandler);
            return null;
        } else {
            return targetChat;
        }
    }

    private static class UpdatesHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.UpdateNewCallbackQuery.CONSTRUCTOR: {
                    TdApi.UpdateNewCallbackQuery newCallbackQuery = (TdApi.UpdateNewCallbackQuery) object;
                    //获取sender User信息
                    int senderID = newCallbackQuery.senderUserId;
                    String sender = String.valueOf(senderID);
                    TdApi.User sendUser = getOrQueryUser(senderID);
                    if (Objects.nonNull(sendUser)) {
                        sender = sendUser.firstName + " " + (sendUser.lastName.length() > 0 ? sendUser.lastName : "");
                    }
                    //获取Chat信息
                    long chatId = newCallbackQuery.chatId;
                    String chatName = String.valueOf(chatId);
                    TdApi.Chat targetChat = getOrQueryChat(chatId);
                    if (Objects.nonNull(targetChat)) {
                        chatName = targetChat.title;
                    }

                    if (newCallbackQuery.payload instanceof TdApi.CallbackQueryPayloadData) {
                        byte[] reply = ((TdApi.CallbackQueryPayloadData) newCallbackQuery.payload).data;
                        String replyStr = new String(reply);

                        if (replyStr.startsWith("nobot")) {
                            boolean done = false;
                            String[] split = replyStr.split("\\^");
                            if (split.length == 2) {
                                String userIdAndChatId = split[1];
                                String[] userIdChatIdSplit = userIdAndChatId.split("@");
                                if (userIdChatIdSplit.length == 2) {
                                    String replyMarkUserId = userIdChatIdSplit[0];
                                    String replyMarkChatId = userIdChatIdSplit[1];
                                    if (getChatId(replyMarkChatId) == chatId && Integer.parseInt(replyMarkUserId) == senderID) {
                                        logger.info(String.format("解封 %s@%s %s@%s", sender, chatName, senderID, chatId)); //打印文本
                                        client.send(new TdApi.SetChatMemberStatus(newCallbackQuery.chatId, newCallbackQuery.senderUserId, new TdApi.ChatMemberStatusRestricted(true, 0, new TdApi.ChatPermissions(true, true, false, true, true, false, true, false))), defaultHandler);
                                        client.send(new TdApi.AnswerCallbackQuery(newCallbackQuery.id, "您可以自由发言", true, null, 1), defaultHandler);
                                        done = true;
                                    }
                                }
                            }
                            if (!done) {
                                client.send(new TdApi.AnswerCallbackQuery(newCallbackQuery.id, "不要瞎点！", true, null, 1), defaultHandler);
                            }
                        } else if (replyStr.startsWith("admin_pass")) {
                            String[] split = replyStr.split("\\^");
                            if (split.length == 2) {
                                String userIdAndChatId = split[1];
                                String[] userIdChatIdSplit = userIdAndChatId.split("@");
                                if (userIdChatIdSplit.length == 2) {
                                    String replyMarkUserId = userIdChatIdSplit[0];
                                    String replyMarkChatId = userIdChatIdSplit[1];
                                    if (getChatId(replyMarkChatId) == chatId) {
                                        client.send(new TdApi.GetChatAdministrators(chatId), (result) -> {
                                            if (result instanceof TdApi.ChatAdministrators) {
                                                TdApi.ChatAdministrators chatAdministrators = (TdApi.ChatAdministrators) result;
                                                Set<Integer> adminUserIds = Arrays.stream(chatAdministrators.administrators)
                                                        .filter(admin->admin.isOwner)
                                                        .map(admin -> admin.userId)
                                                        .collect(Collectors.toSet());
                                                if(adminUserIds.contains(senderID)){
                                                    client.send(new TdApi.SetChatMemberStatus(newCallbackQuery.chatId, Integer.parseInt(replyMarkUserId), new TdApi.ChatMemberStatusRestricted(true, 0, new TdApi.ChatPermissions(true, true, false, true, true, false, true, false))), defaultHandler);
                                                    client.send(new TdApi.AnswerCallbackQuery(newCallbackQuery.id, "成功解封"+replyMarkUserId, true, null, 1), defaultHandler);
                                                }else {
                                                    client.send(new TdApi.AnswerCallbackQuery(newCallbackQuery.id, "再瞎点就报警了！", true, null, 1), defaultHandler);
                                                }
                                            }
                                        });
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
                case TdApi.UpdateNewMessage.CONSTRUCTOR: {//todo：接受消息
                    TdApi.UpdateNewMessage message = (TdApi.UpdateNewMessage) object;

                    //获取sender User信息
                    int senderID = message.message.senderUserId;
                    String sender = String.valueOf(senderID);
                    TdApi.User sendUser = getOrQueryUser(senderID);
                    if (Objects.nonNull(sendUser)) {
                        sender = sendUser.firstName + " " + (sendUser.lastName.length() > 0 ? sendUser.lastName : "");
                    }
                    //获取Chat信息
                    long chatId = message.message.chatId;
                    String chatName = String.valueOf(chatId);
                    TdApi.Chat targetChat = getOrQueryChat(chatId);
                    if (Objects.nonNull(targetChat)) {
                        chatName = targetChat.title;
                    }

                    TdApi.MessageContent messageContent = message.message.content;
                    if (messageContent instanceof TdApi.MessageText) {
                        TdApi.MessageText textMessage = (TdApi.MessageText) messageContent;
                        TdApi.FormattedText formattedText = textMessage.text;
                        String log = String.format("(%s) @%s (%s)@%s\n%s\n", sender, chatName, senderID, chatId, formattedText.text);
                        logger.info("\n" + log); //打印文本
                    } else {
                        //打印其他类型
//                        System.out.println(JSONObject.toJSON(messageContent));
                    }

                    if (adminChatId != null && chatId == getChatId(adminChatId)) {//管理群组的消息做特殊处理
                        // 本群组的所有消息类型都日志记录
                        logger.info(newLine + "(" + sender + ")@" + chatName + " " + senderID + "@" + chatId + newLine + object);
                        if (message.message.content instanceof TdApi.MessageChatAddMembers && message.message.date>bootUnixTime) {
                            int[] memberUserIds = ((TdApi.MessageChatAddMembers) message.message.content).memberUserIds;
                            String finalSender = sender;
                            String finalChatName = chatName;
                            Arrays.stream(memberUserIds).forEach(newMemberId->{
                                String msg = "欢迎来到本群组~~~" + newLine;

                                TdApi.ReplyMarkupInlineKeyboard replyMarkup = null;
                                if (me != null && me.type instanceof TdApi.UserTypeBot) {//如果是bot，则增加防bot设置
                                    TdApi.InlineKeyboardButton[] rowBlogAndGithub = {new TdApi.InlineKeyboardButton("博客地址", new TdApi.InlineKeyboardButtonTypeUrl("http://arloor.com")), new TdApi.InlineKeyboardButton("Github", new TdApi.InlineKeyboardButtonTypeUrl("https://github.com/arloor"))};
                                    TdApi.InlineKeyboardButton[] notBot = {new TdApi.InlineKeyboardButton("我不是机器人", new TdApi.InlineKeyboardButtonTypeCallback(String.format("nobot^%s@%s", newMemberId, chatId).getBytes()))};
                                    TdApi.InlineKeyboardButton[] adminPass = {new TdApi.InlineKeyboardButton("PASS[管理员]", new TdApi.InlineKeyboardButtonTypeCallback(String.format("admin_pass^%s@%s", newMemberId, chatId).getBytes()))};
                                    replyMarkup = new TdApi.ReplyMarkupInlineKeyboard(new TdApi.InlineKeyboardButton[][]{notBot, rowBlogAndGithub, adminPass});
                                    logger.info(String.format("封禁新加群的%s@%s %s@%s", finalSender, finalChatName, newMemberId, chatId)); //打印文本
                                    client.send(new TdApi.SetChatMemberStatus(chatId, newMemberId, new TdApi.ChatMemberStatusRestricted(true, 0, new TdApi.ChatPermissions(false, false, false, false, false, false, false, false))), defaultHandler);
                                    msg += "请点击“我不是机器人”获取发言权限" + newLine
                                            + "—— From电报Tdlib jni应用";
                                } else {
                                    msg += "博客地址：http://arloor.com" + newLine
                                            + "Github：https://github.com/arloor" + newLine
                                            + "—— From电报Tdlib jni应用";
                                }
                                TdApi.InputMessageContent content = new TdApi.InputMessageText(new TdApi.FormattedText(msg, null), false, true);
                                client.send(new TdApi.SendMessage(chatId, message.message.id, null, replyMarkup, content), defaultHandler);
                            });

                        }
                    }
                    break;
                }
                case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                    onAuthorizationStateUpdated(((TdApi.UpdateAuthorizationState) object).authorizationState);
                    break;
                case TdApi.UpdateUser.CONSTRUCTOR:
                    TdApi.UpdateUser updateUser = (TdApi.UpdateUser) object;
                    users.put(updateUser.user.id, updateUser.user);
                    break;
                case TdApi.UpdateUserStatus.CONSTRUCTOR: {
                    TdApi.UpdateUserStatus updateUserStatus = (TdApi.UpdateUserStatus) object;
                    TdApi.User user = users.get(updateUserStatus.userId);
                    synchronized (user) {
                        user.status = updateUserStatus.status;
                    }
                    break;
                }
                case TdApi.UpdateBasicGroup.CONSTRUCTOR:
                    TdApi.UpdateBasicGroup updateBasicGroup = (TdApi.UpdateBasicGroup) object;
                    basicGroups.put(updateBasicGroup.basicGroup.id, updateBasicGroup.basicGroup);
                    break;
                case TdApi.UpdateSupergroup.CONSTRUCTOR:
                    TdApi.UpdateSupergroup updateSupergroup = (TdApi.UpdateSupergroup) object;
                    supergroups.put(updateSupergroup.supergroup.id, updateSupergroup.supergroup);
                    break;
                case TdApi.UpdateSecretChat.CONSTRUCTOR:
                    TdApi.UpdateSecretChat updateSecretChat = (TdApi.UpdateSecretChat) object;
                    secretChats.put(updateSecretChat.secretChat.id, updateSecretChat.secretChat);
                    break;

                case TdApi.UpdateNewChat.CONSTRUCTOR: {
                    TdApi.UpdateNewChat updateNewChat = (TdApi.UpdateNewChat) object;
                    TdApi.Chat chat = updateNewChat.chat;
                    synchronized (chat) {
                        chats.put(chat.id, chat);

                        long order = chat.order;
                        chat.order = 0;
                        setChatOrder(chat, order);
                    }
                    break;
                }
                case TdApi.UpdateChatTitle.CONSTRUCTOR: {
                    TdApi.UpdateChatTitle updateChat = (TdApi.UpdateChatTitle) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.title = updateChat.title;
                    }
                    break;
                }
                case TdApi.UpdateChatPhoto.CONSTRUCTOR: {
                    TdApi.UpdateChatPhoto updateChat = (TdApi.UpdateChatPhoto) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.photo = updateChat.photo;
                    }
                    break;
                }
                case TdApi.UpdateChatChatList.CONSTRUCTOR: {
                    TdApi.UpdateChatChatList updateChat = (TdApi.UpdateChatChatList) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (mainChatList) { // to not change Chat.chatList while mainChatList is locked
                        synchronized (chat) {
                            assert chat.order == 0; // guaranteed by TDLib
                            chat.chatList = updateChat.chatList;
                        }
                    }
                    break;
                }
                case TdApi.UpdateChatLastMessage.CONSTRUCTOR: {
                    TdApi.UpdateChatLastMessage updateChat = (TdApi.UpdateChatLastMessage) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastMessage = updateChat.lastMessage;
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateChatOrder.CONSTRUCTOR: {
                    TdApi.UpdateChatOrder updateChat = (TdApi.UpdateChatOrder) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateChatIsPinned.CONSTRUCTOR: {
                    TdApi.UpdateChatIsPinned updateChat = (TdApi.UpdateChatIsPinned) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.isPinned = updateChat.isPinned;
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateChatReadInbox.CONSTRUCTOR: {
                    TdApi.UpdateChatReadInbox updateChat = (TdApi.UpdateChatReadInbox) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId;
                        chat.unreadCount = updateChat.unreadCount;
                    }
                    break;
                }
                case TdApi.UpdateChatReadOutbox.CONSTRUCTOR: {
                    TdApi.UpdateChatReadOutbox updateChat = (TdApi.UpdateChatReadOutbox) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId;
                    }
                    break;
                }
                case TdApi.UpdateChatUnreadMentionCount.CONSTRUCTOR: {
                    TdApi.UpdateChatUnreadMentionCount updateChat = (TdApi.UpdateChatUnreadMentionCount) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount;
                    }
                    break;
                }
                case TdApi.UpdateMessageMentionRead.CONSTRUCTOR: {
                    TdApi.UpdateMessageMentionRead updateChat = (TdApi.UpdateMessageMentionRead) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount;
                    }
                    break;
                }
                case TdApi.UpdateChatReplyMarkup.CONSTRUCTOR: {
                    TdApi.UpdateChatReplyMarkup updateChat = (TdApi.UpdateChatReplyMarkup) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.replyMarkupMessageId = updateChat.replyMarkupMessageId;
                    }
                    break;
                }
                case TdApi.UpdateChatDraftMessage.CONSTRUCTOR: {
                    TdApi.UpdateChatDraftMessage updateChat = (TdApi.UpdateChatDraftMessage) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.draftMessage = updateChat.draftMessage;
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateChatNotificationSettings.CONSTRUCTOR: {
                    TdApi.UpdateChatNotificationSettings update = (TdApi.UpdateChatNotificationSettings) object;
                    TdApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.notificationSettings = update.notificationSettings;
                    }
                    break;
                }
                case TdApi.UpdateChatDefaultDisableNotification.CONSTRUCTOR: {
                    TdApi.UpdateChatDefaultDisableNotification update = (TdApi.UpdateChatDefaultDisableNotification) object;
                    TdApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.defaultDisableNotification = update.defaultDisableNotification;
                    }
                    break;
                }
                case TdApi.UpdateChatIsMarkedAsUnread.CONSTRUCTOR: {
                    TdApi.UpdateChatIsMarkedAsUnread update = (TdApi.UpdateChatIsMarkedAsUnread) object;
                    TdApi.Chat chat = chats.get(update.chatId);
                    synchronized (chat) {
                        chat.isMarkedAsUnread = update.isMarkedAsUnread;
                    }
                    break;
                }
                case TdApi.UpdateChatIsSponsored.CONSTRUCTOR: {
                    TdApi.UpdateChatIsSponsored updateChat = (TdApi.UpdateChatIsSponsored) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.isSponsored = updateChat.isSponsored;
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }

                case TdApi.UpdateUserFullInfo.CONSTRUCTOR:
                    TdApi.UpdateUserFullInfo updateUserFullInfo = (TdApi.UpdateUserFullInfo) object;
                    usersFullInfo.put(updateUserFullInfo.userId, updateUserFullInfo.userFullInfo);
                    break;
                case TdApi.UpdateBasicGroupFullInfo.CONSTRUCTOR:
                    TdApi.UpdateBasicGroupFullInfo updateBasicGroupFullInfo = (TdApi.UpdateBasicGroupFullInfo) object;
                    basicGroupsFullInfo.put(updateBasicGroupFullInfo.basicGroupId, updateBasicGroupFullInfo.basicGroupFullInfo);
                    break;
                case TdApi.UpdateSupergroupFullInfo.CONSTRUCTOR:
                    TdApi.UpdateSupergroupFullInfo updateSupergroupFullInfo = (TdApi.UpdateSupergroupFullInfo) object;
                    supergroupsFullInfo.put(updateSupergroupFullInfo.supergroupId, updateSupergroupFullInfo.supergroupFullInfo);
                    break;
                default:
//                     print("Unsupported update:" + newLine + object);
            }
        }
    }

    private static class AuthorizationRequestHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.Error.CONSTRUCTOR:
                    System.err.println("Receive an error:" + newLine + object);
                    onAuthorizationStateUpdated(null); // repeat last action
                    break;
                case TdApi.Ok.CONSTRUCTOR:
                    // result is already received through UpdateAuthorizationState, nothing to do
                    break;
                default:
                    System.err.println("Receive wrong response from TDLib:" + newLine + object);
            }
        }
    }
}
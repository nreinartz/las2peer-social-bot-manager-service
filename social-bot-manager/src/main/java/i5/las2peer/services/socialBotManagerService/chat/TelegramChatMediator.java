package i5.las2peer.services.socialBotManagerService.chat;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Optional;
import java.util.Vector;

import javax.ws.rs.core.MediaType;

import com.pengrad.telegrambot.model.request.*;
import com.pengrad.telegrambot.request.*;
import net.minidev.json.JSONArray;
import net.minidev.json.parser.JSONParser;
import org.apache.commons.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.File;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.GetMeResponse;
import com.pengrad.telegrambot.response.SendResponse;

import i5.las2peer.connectors.webConnector.client.ClientResponse;
import i5.las2peer.connectors.webConnector.client.MiniClient;
import i5.las2peer.services.socialBotManagerService.model.IncomingMessage;
import net.minidev.json.JSONObject;

/**
 * The TelegramChatMediator handles the communication with the Telegram
 * Messenger Application. This includes the parsing of incoming event data and
 * the sending of data to telegram channels.
 *
 */
public class TelegramChatMediator extends ChatMediator {

	TelegramBot bot;
	private TelegramMessageCollector messageCollector;
	// private TelegramMessageCollector conversationPathCollector;
	MiniClient client;

	private static HashMap<String, TelegramChatMediator> botInstances = new HashMap<String, TelegramChatMediator>();

	public TelegramChatMediator(String authToken) throws AuthTokenException {
		super(authToken);

		// Apparently bot instances are not closed? But we need to disconnect updates
		// handler
		if (botInstances.containsKey(authToken)) {
			TelegramChatMediator other = botInstances.get(authToken);
			other.close();
		}

		try {
			this.bot = new TelegramBot(authToken);

			this.bot.setUpdatesListener(updates -> {
				updates.forEach(this::handleUpdate);
				return UpdatesListener.CONFIRMED_UPDATES_ALL;
			});

			botInstances.put(authToken, this);
		} catch (Exception e) {
			if (e.toString().toLowerCase().contains("404")) {
				throw new AuthTokenException("Authentication Token is faulty!");
			} else
				throw e;

		}
		this.client = new MiniClient();
		client.setConnectorEndpoint("https://api.telegram.org/bot" + authToken);
		messageCollector = new TelegramMessageCollector();
	}

	private void handleUpdate(Update update) {
		System.out.println("update: " + update.toString());

		try {
			String data = null;
			Message message = update.message();
			// check if a button click was detected
			if (update.callbackQuery() != null) {
				System.out.println("inside callback_query");
				CallbackQuery callback_query = update.callbackQuery();

				message = callback_query.message();
				data = callback_query.data();
			} else if (update.editedMessage() != null) {
				System.out.println("inside edited message");
				message = update.editedMessage();
			} else {
				message = update.message();
			}

			String channel = message.chat().id().toString();
			String user = message.from().firstName();
			String text = message.text();
			String messageId = message.messageId().toString();
			if (data != null) {
				// the data field can be used to know which button was clicked
				// (the text returned is not the text from the button, but the text above the
				// button, therefore irrelevant)
				text = data;
			}

			String timestamp = message.date().toString();

			if (channel == null || user == null || (text == null && message.document() == null) || timestamp == null
					|| messageId == null)
				throw new InvalidChatMessageException("missing message fields");

			this.showAction(channel, ChatAction.typing);

			// message with document
			if (message.document() != null) {
				String fileName = message.document().fileName();
				String mimeType = message.document().mimeType();
				String fileId = message.document().fileId();
				String fileBody = getFile(fileId);
				messageCollector.addMessage(
						new ChatMessage(channel, user, text, timestamp, messageId, fileName, mimeType, fileBody));
			} else if (message.audio() != null) {
				String fileId = message.audio().fileId();
				String mimeType = message.audio().mimeType();
				String fileBody = getFile(fileId);
				messageCollector.addMessage(
						new ChatMessage(channel, user, text, timestamp, messageId, mimeType, fileBody));
			} else {
				messageCollector.addMessage(new ChatMessage(channel, user, text, timestamp, messageId));
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public Vector<ChatMessage> getMessages() {
		return messageCollector.getMessages();
	}

	public String getBotName() {
		GetMe request = new GetMe();
		GetMeResponse response = bot.execute(request);
		if (response.isOk() && response.user() != null) {

			String username = response.user().username();
			System.out.println("request botname: " + username);
			return username;
		}

		return null;
	}

	/**
	 * Sends a plain text message to telegram messenger channel
	 */
	@Override
	public Boolean sendMessageToChannel(String channel, String text, HashMap<String, IncomingMessage> hashMap,
			String type, Optional<String> id) {

		System.out.println("send plain message to telegram channel " + channel + ", size: " + text.length());
		assert channel != null;
		assert text != null;

		SendMessage baseMessageRequest = new SendMessage(channel, text);

		if (bot.execute(baseMessageRequest.parseMode(ParseMode.MarkdownV2)).isOk()) {
			return true;
		} else if (bot.execute(baseMessageRequest.parseMode(ParseMode.Markdown)).isOk()) {
			return true;
		} else if (bot.execute(baseMessageRequest).isOk()) {
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void sendFileMessageToChannel(String channel, String fileBody, String fileName, String fileType, String text,
			Optional<String> id) {
		String caption = text;
		System.out.println("Send File to Telegram channel: " + channel);

		try {

			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			JsonElement je = JsonParser.parseString(fileBody);
			fileBody = gson.toJson(je);
			System.out.println(fileBody.substring(0, 160));

		} catch (Exception e) {
			e.printStackTrace();
		}

		byte[] bytes = fileBody.getBytes(StandardCharsets.UTF_8);
		SendDocument request = new SendDocument(channel, bytes);

		if (!caption.contentEquals(""))
			request.caption(caption);
		if (fileName != null && !fileName.contentEquals(""))
			request.fileName(fileName);

		request.replyMarkup(new ReplyKeyboardRemove());
		BaseResponse res = bot.execute(request);
		System.out.println(String.valueOf(res.isOk()) + " " + res.errorCode() + " " + res.description());
	}

	@Override
	public void sendFileMessageToChannel(String channel, java.io.File f, String text, Optional<String> id) {
		String caption = "";
		System.out.println("Send File to Telegram channel: " + channel);

		SendDocument request = new SendDocument(channel, f);

		if (!caption.contentEquals(""))
			request.caption(caption);

		request.replyMarkup(new ReplyKeyboardRemove());
		BaseResponse res = bot.execute(request);
		System.out.println(String.valueOf(res.isOk()) + " " + res.errorCode() + " " + res.description());
	}

	@Override
	public String getChannelByEmail(String email) {
		return null;
	}

	@Override
	public void close() {
		this.bot.removeGetUpdatesListener();
	}

	private String getFile(String fileId) {

		GetFile request = new GetFile(fileId);
		GetFileResponse response = bot.execute(request);
		File file = response.file();
		System.out.println("file received");
		String path = "https://api.telegram.org/file/bot" + authToken + "/" + file.filePath();
		URL url;
		String data = null;
		try {
			url = new URL(path);
			data = IOUtils.toString(url);

		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return data;
	}

	@Override
	public void sendBlocksMessageToChannel(String channel, String blocks, String authToken,
			HashMap<String, IncomingMessage> hashMap, Optional<String> id) {

		System.out.println("send interactive message to telegram channel " + channel);
		assert channel != null;
		assert blocks != null;

		try {
			JsonObject blocksJO = JsonParser.parseString(blocks).getAsJsonObject();
			String text = blocksJO.get("text").getAsString();
			String inline_keyboard = blocksJO.get("inline_keyboard").getAsString();

			InlineKeyboardMarkup markup = parseInlineKeyboardMarkup(inline_keyboard);

			SendMessage request = new SendMessage(channel, text);
			request.replyMarkup(markup);

			BaseResponse res = bot.execute(request);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public void updateBlocksMessageToChannel(String channel, String blocks, String authToken, String ts,
			Optional<String> id) {

	}

	@Override
	public void updateBlocksMessageToChannel(String channel, String blocks, String authToken, String ts) {
		super.updateBlocksMessageToChannel(channel, blocks, authToken, ts);
	}

	private InlineKeyboardMarkup parseInlineKeyboardMarkup(String blocks) {
		try {
			JsonArray blocksJA = JsonParser.parseString(blocks).getAsJsonArray();

			InlineKeyboardButton[][] allButtons = new InlineKeyboardButton[blocksJA.size()][];

			int i = 0;
			for (Object o : blocksJA) {
				JSONArray currO = (JSONArray) o;
				InlineKeyboardButton[] currButtons = new InlineKeyboardButton[currO.size()];

				int x = 0;
				for (Object so : currO) {
					JSONObject currSO = (JSONObject) so;
					String text = currSO.getAsString("text");
					// currently used, since passed on unicodes get parsed wrong
					// will be fixed soon
					text = text.replaceAll(":check:", "\u2713");
					InlineKeyboardButton button = new InlineKeyboardButton(text);
					button.callbackData(currSO.getAsString("callback_data"));
					if (currSO.containsKey("url")) {
						button.url(currSO.getAsString("url"));
					}
					currButtons[x] = button;
					x++;
				}

				allButtons[i] = currButtons;
				i++;
			}

			InlineKeyboardMarkup markup = new InlineKeyboardMarkup(allButtons);
			return markup;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public void editMessage(String channel, String messageId, String message, Optional<String> id) {

		System.out.println("editing telegram message with id " + messageId + " and new message text " + message);

		try {
			JSONParser p = new JSONParser();
			JSONObject messageTextJO = (JSONObject) p.parse(message);
			String text = messageTextJO.getAsString("text");

			String inline_keyboard = "";
			try {
				inline_keyboard = messageTextJO.getAsString("inline_keyboard");
			} catch (Exception ex) {
				// create empty inline keyboard
				inline_keyboard = "[[]]";
			}

			Object chatId = channel;
			int messageIdInt = Integer.parseInt(messageId);
			System.out.println("text: " + text + " inline keyboard: " + inline_keyboard);
			EditMessageText request = new EditMessageText(chatId, messageIdInt, text);

			InlineKeyboardMarkup markup = parseInlineKeyboardMarkup(inline_keyboard);
			request.replyMarkup(markup);

			BaseResponse res = bot.execute(request);
			System.out.println("res: " + String.valueOf(res.isOk()) + " " + res.errorCode() + " " + res.description());
		} catch (Exception e) {
			System.out.println("editing message did not work");
			e.printStackTrace();
		}

	}

	public TelegramMessageCollector getMessageCollector() {
		return messageCollector;
	}

	@Override
	public void sendBlocksMessageToChannel(String channel, String blocks, String authToken) {
		sendBlocksMessageToChannel(channel, blocks, authToken, null, Optional.empty());
	}

	/**
	 * Shows an indication to the user about what the next bots action is
	 * 
	 * @param channel id of channel indication should be shown
	 * @param action  type of indication shown
	 * @return request was successful (true) or failed (false)
	 */
	public boolean showAction(String channel, ChatAction action) {

		SendChatAction typingAction = new SendChatAction(channel, action);
		BaseResponse response = this.bot.execute(typingAction);
		return response.isOk();
	}

}

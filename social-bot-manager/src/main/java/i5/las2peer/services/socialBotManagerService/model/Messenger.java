package i5.las2peer.services.socialBotManagerService.model;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Optional;
import java.util.Random;
import java.util.Vector;

import javax.websocket.DeploymentException;

import com.google.gson.Gson;

import i5.las2peer.services.socialBotManagerService.chat.*;
import i5.las2peer.services.socialBotManagerService.chat.github.GitHubAppHelper;
import i5.las2peer.services.socialBotManagerService.chat.github.GitHubIssueMediator;
import i5.las2peer.services.socialBotManagerService.chat.github.GitHubPRMediator;
import i5.las2peer.services.socialBotManagerService.database.SQLDatabase;
import i5.las2peer.services.socialBotManagerService.nlu.Entity;
import i5.las2peer.services.socialBotManagerService.nlu.Intent;
import i5.las2peer.services.socialBotManagerService.nlu.RasaNlu;
import i5.las2peer.services.socialBotManagerService.parser.ParseBotException;
import jnr.ffi.annotations.In;

import java.util.UUID;

public class Messenger {
	private String name;

	// URL of the social bot manager service (used for setting up the webhook)
	private String url;

	private ChatMediator chatMediator;

	/**
	 * The messenger application provider this object corresponds to
	 */
	private ChatService chatService;

	/**
	 * Contains all IncomingMessages that are reachable from the start state
	 * Key: intent keyword
	 * Value: IncomingMessage object
	 */
	private HashMap<String, IncomingMessage> rootChildren;

	/**
	 * Used for keeping conversation state per channel
	 * Key: channel ID
	 * Value: current state of the conversation (last IncomingMessage)
	 * 
	 */
	private HashMap<String, IncomingMessage> stateMap;
	/**
	 * Used for keeping remembering entities during conversation state per channel
	 * Key: channel ID
	 * Value: Collection of entities that were recognized during the conversation
	 */
	private HashMap<String, Collection<Entity>> recognizedEntities;
	/**
	 * Used for keeping context between assessment and non-assessment states
	 * Key: channel ID
	 * Value: current NLU model ID
	 */
	private HashMap<String, String> currentNluModel;
	/**
	 * Used to know to which Function the received intents/messages are to be sent
	 * Is additionally used to check if we are currently communicating with a
	 * service(if set, then yes otherwise no)
	 * Key: channel ID
	 * Value: current triggered function name
	 */
	private HashMap<String, String> triggeredFunction;
	/**
	 * Key: channel ID
	 * Value: number of times a default message was given out in a conversation
	 * state
	 */
	private HashMap<String, Integer> defaultAnswerCount;

	/**
	 * Used to store the current state of the conversation in case a command is
	 * triggered. Whenever this happens the current state is stored in this map and
	 * we jump into the command's conversation path. After the command is finished
	 * the state is restored.
	 * Key: channel ID
	 * Value: current state of the conversation (last IncomingMessage)
	 */
	private HashMap<String, IncomingMessage> storedSession;

	private HashMap<String, HashMap<String, String>> userVariables;

	private Random random;

	private SQLDatabase db;

	public Messenger(String id, String chatService, String token, SQLDatabase database)
			throws IOException, DeploymentException, ParseBotException, AuthTokenException {

		// this.rasa = new RasaNlu(rasaUrl);
		// this.rasaAssessment = new RasaNlu(rasaAssessmentUrl);
		this.db = database;
		// Chat Mediator
		this.chatService = ChatService.fromString(chatService);
		switch (this.chatService) {
			case SLACK:
				this.chatMediator = new SlackChatMediator(token);
				break;
			case TELEGRAM:
				this.chatMediator = new TelegramChatMediator(token);
				String username = ((TelegramChatMediator) this.chatMediator).getBotName();
				if (username != null)
					this.name = username;
				break;
			case ROCKET_CHAT:
				this.chatMediator = new RocketChatMediator(token, database, new RasaNlu("rasaUrl"));
				break;
			case MOODLE_CHAT:
				this.chatMediator = new MoodleChatMediator(token);
				break;
			case MOODLE_FORUM:
				this.chatMediator = new MoodleForumMediator(token);
				break;
			case GITHUB_ISSUES:
				try {
					this.chatMediator = new GitHubIssueMediator(token);
				} catch (GitHubAppHelper.GitHubAppHelperException e) {
					throw new AuthTokenException(e.getMessage());
				}
				break;
			case GITHUB_PR:
				try {
					this.chatMediator = new GitHubPRMediator(token);
				} catch (GitHubAppHelper.GitHubAppHelperException e) {
					throw new AuthTokenException(e.getMessage());
				}
				break;
			case RESTful_Chat:
				this.chatMediator = new RESTfulChatMediator(token);
				System.out.println("RESTful Chat selected");
				break;
			default:
				throw new ParseBotException("Unimplemented chat service: " + chatService);
		}
		System.out.println("no exceptions");

		this.name = id;
		this.rootChildren = new HashMap<String, IncomingMessage>();
		this.stateMap = new HashMap<String, IncomingMessage>();
		this.recognizedEntities = new HashMap<String, Collection<Entity>>();
		this.random = new Random();
		// Initialize the assessment setup
		this.currentNluModel = new HashMap<String, String>();
		this.triggeredFunction = new HashMap<String, String>();
		this.defaultAnswerCount = new HashMap<String, Integer>();
		this.storedSession = new HashMap<String, IncomingMessage>();
		this.userVariables = new HashMap<String, HashMap<String, String>>();
	}

	public String getName() {
		return name;
	}

	public ChatService getChatService() {
		return chatService;
	}

	public void addMessage(IncomingMessage msg) {
		if (msg.getIntentKeyword().contains("defaultX")) {
			this.rootChildren.put("defaultX", msg);
		} else
			this.rootChildren.put(msg.getIntentKeyword(), msg);
	}

	public HashMap<String, IncomingMessage> getRootChildren() {
		return this.rootChildren;
	}

	public ChatMediator getChatMediator() {
		return this.chatMediator;
	}

	public IncomingMessage checkDefault(IncomingMessage state, ChatMessage message) {
		if (this.rootChildren.get("defaultX") != null && Integer.valueOf(
				this.rootChildren.get("defaultX").getIntentKeyword().split("defaultX")[1]) > this.defaultAnswerCount
						.get(message.getChannel())) {
			IncomingMessage newState = this.rootChildren.get("defaultX");
			newState.followupMessages = state.followupMessages;
			state = newState;
			this.defaultAnswerCount.put(message.getChannel(), this.defaultAnswerCount.get(message.getChannel()) + 1);
		} else {
			state = this.rootChildren.get("default");
			this.defaultAnswerCount.put(message.getChannel(), 0);
		}
		return state;
	}

	private void addEntityToRecognizedList(String channel, Collection<Entity> entities) {

		Collection<Entity> recognizedEntitiesNew = recognizedEntities.get(channel);
		if (recognizedEntitiesNew != null) {
			for (Entity entity : entities) {
				recognizedEntitiesNew.add(entity);
			}
			recognizedEntities.put(channel, recognizedEntitiesNew);
		}
	}
	// set the context of the specified channel
	/*
	 * public void setContext(String channel, String contextName){
	 * context.put(channel, contextName);
	 * 
	 * }
	 */

	/*
	 * public String getEmail(String channel) throws IOException, SlackApiException
	 * { return chatMediator.getEmail(channel); };
	 */

	public void setContextToBasic(String channel, String userid) {
		triggeredFunction.remove(channel);
		IncomingMessage state = this.stateMap.get(channel);
		if (state != null) {
			if (state.getFollowingMessages() == null || state.getFollowingMessages().size() == 0) {
				System.out.println("Conversation flow ended now");
				if (storedSession.containsKey(channel)) {
					stateMap.put(channel, storedSession.get(channel));
					state = storedSession.get(channel);
					storedSession.remove(channel);
					System.out.println("Restoring session");
					String response = state.getResponse(random);
					if (response != null && !response.equals("")) {
						System.out.println("Found old message");

						this.chatMediator.sendMessageToChannel(channel, replaceVariables(channel, response), "text");
					}
				}
			} else if (state.getFollowingMessages().get("") != null) {
				// check whether bot action needs to be triggered without user input
				state = state.getFollowingMessages().get("");
				stateMap.put(channel, state);
				if (!state.getResponse(random).equals("")) {
					if (this.chatService == ChatService.RESTful_Chat && state.getFollowingMessages() != null
							&& !state.getFollowingMessages().isEmpty()) {
						this.chatMediator.sendMessageToChannel(channel,
								replaceVariables(channel, state.getResponse(random)), state.getFollowingMessages(),
								"text");

					} else {
						this.chatMediator.sendMessageToChannel(channel,
								replaceVariables(channel, state.getResponse(random)), "text");

					}
				}
				/*
				 * if (state.getResponse(random).triggeredFunctionId != null
				 * && !state.getResponse(random).triggeredFunctionId.equals("")) {
				 * ChatMessage chatMsg = new ChatMessage(channel, userid, "Empty Message");
				 * this.triggeredFunction.put(channel,
				 * state.getResponse(random).triggeredFunctionId);
				 * this.chatMediator.getMessageCollector().addMessage(chatMsg);
				 * }
				 */
			} else {
				// If only message to be sent
				String response = state.getResponse(random);
				if (response != null && !response.equals("")) {
					this.chatMediator.sendMessageToChannel(channel, replaceVariables(channel, response),
							state.getFollowingMessages(), state.getFollowupMessageType(), Optional.of(userid));
				}
				if (state.getFollowingMessages().size() == 0) {
					this.stateMap.remove(channel);

				}
			}
		} else {
		}
	}

	public String getContext(String channel, String user) {
		return this.triggeredFunction.get(channel);
	}

	public HashMap<String, HashMap<String, String>> getUserVariables() {
		return userVariables;
	}

	public void setUserVariables(HashMap<String, HashMap<String, String>> userVariables) {
		this.userVariables = userVariables;
	}

	public void resetUserVariables(String channel) {
		this.userVariables.get(channel).clear();
	}

	public void addVariable(String channel, String key, String value) {
		HashMap<String, String> variables = this.getUserVariables().get(channel);
		variables.put(key, value);
		this.userVariables.put(channel, variables);
	}

	public String replaceVariables(String channel, String text) {
		HashMap<String, String> variables = this.getUserVariables().get(channel);
		if (variables != null) {
			for (String key : variables.keySet()) {
				String composed = "[" + key + "]";
				text = text.replace(composed, variables.get(key));
			}
		}
		String split[] = text.split("\\[");
		for (int i = 1; i < split.length; i++) {

			String name = split[i].split("\\]")[0];
			String val = getEntityValue(channel, name);
			if (!val.equals("")) {
				String composed = "[" + name + "]";
				text = text.replace(composed, val);

			}
		}
		return text;
	}

	// Handles simple responses ("Chat Response") directly, logs all messages and
	// extracted intents into `messageInfos` for further processing later on.
	public void handleMessages(ArrayList<MessageInfo> messageInfos, Bot bot) {
		Vector<ChatMessage> newMessages = this.chatMediator.getMessages();
		for (ChatMessage message : newMessages) {
			try {
				// // If a channel/user pair still isn't assigned to a state, assign it to null
				// if (this.stateMap.get(message.getChannel()) == null) {
				// HashMap<String, IncomingMessage> initMap = new HashMap<String,
				// IncomingMessage>();
				// initMap.put(message.getUser(), null);
				// this.stateMap.put(message.getChannel(), initMap);
				// }

				// If a channel/user pair still isn't assigned to a NLU Model, assign it to the
				// Model 0
				if (this.currentNluModel.get(message.getChannel()) == null) {
					this.currentNluModel.put(message.getChannel(), "0");
				}

				// If channel/user pair is not assigned to a triggered function, assign it to
				// null
				// if (this.triggeredFunction.get(message.getChannel()) == null) {
				// HashMap<String, String> initMap = new HashMap<String, String>();
				// initMap.put(message.getUser(), null);
				// this.triggeredFunction.put(message.getChannel(), initMap);
				// }

				String triggeredFunctionId = null;
				final IncomingMessage previousIncomingMessage = this.stateMap.get(message.getChannel());
				IncomingMessage currentContext = previousIncomingMessage;
				UUID conversationId = null;
				Boolean contextOn = false;
				/**
				 * Check if the message is a command or a normal message
				 */
				boolean messageIsCommand = message.getText().startsWith("!");
				/**
				 * Check if the bot is currently in a function context. This is the case if
				 * the bot is currently communicating with a service.
				 */
				boolean inFunctionContext = this.triggeredFunction.containsKey(message.getChannel());
				boolean messageContainsFile = message.getFileName() != null;

				if (!this.userVariables.containsKey(message.getChannel())) {
					this.userVariables.put(message.getChannel(), new HashMap<String, String>());
				}

				if (this.defaultAnswerCount.get(message.getChannel()) == null) {
					this.defaultAnswerCount.put(message.getChannel(), 0);
				}
				Intent intent = this.determineIntent(message, bot);
				System.out.println("found following intent: " + intent.getKeyword());
				try {
					safeEntities(message, bot, intent);
				} catch (Exception e) {
					e.printStackTrace();
				}
				if (previousIncomingMessage == null) {
					conversationId = UUID.randomUUID();
					System.out.println("No current state, we will start from scratch. Generated Conversation  id is: "
							+ conversationId.toString());
				} else {
					conversationId = previousIncomingMessage.getConversationId();
					if (conversationId == null) {
						throw new Error("Conversation id of Previous IncomingMessage is null");
					}
					System.out.println(
							"Current state: " + previousIncomingMessage.getIntentKeyword() + " with conversation id: "
									+ conversationId.toString());
				}

				if (messageIsCommand) {
					if (!this.rootChildren.containsKey(intent.getKeyword())) {
						// in case a command is triggered which does not exist
						this.chatMediator.sendMessageToChannel(message.getChannel(), "",
								new HashMap<String, IncomingMessage>(), "text");
						return;
					}
					if (!intent.getKeyword().equals("exit")) {
						this.storeContextInSession(message.getChannel(), currentContext);
						currentContext = null; // move back to root state
					}

					if (storedSession.containsKey(message.getChannel())) {
						// think about something else to do here
						// this.chatMediator.sendMessageToChannel(message.getChannel(),"Dont start
						// command inside command lol","text");
					}
				}

				if (inFunctionContext) {
					if (message.getFileName() == null && intent.getConfidence() < 0.40f) {
						// Default message if the message does not contain a file or the Intent was too
						// low
						intent = new Intent("default", "", "");
					} else if (!this.rootChildren.get("0").expectsFile()) {
						// if no Incoming Message is fitting, return default message
						intent = new Intent("default", "", "");
					}

					if (currentContext == null || this.rootChildren.get("0") == null
							|| !currentContext.getIntentKeyword().contains("defaultX")) {
						this.defaultAnswerCount.put(message.getChannel(), 0);
					}
					messageInfos.add(new MessageInfo(message, intent, triggeredFunctionId, bot.getName(),
							"", contextOn, recognizedEntities.get(message.getChannel()), this.getName()));
					return;
				}

				// not in function context
				if (intent.getKeyword().equals("exit")) {
					this.recognizedEntities.remove(message.getChannel());
					currentContext = this.storedSession.get(message.getChannel()); // get previous context from stored
																					// session
					this.storedSession.remove(message.getChannel()); // remove stored session
				}

				if (intent.getConfidence() >= 0.40 || messageContainsFile) {
					if (currentContext == null) {
						recognizedEntities.put(message.getChannel(), new ArrayList<Entity>());
						if (messageContainsFile) {
							if (this.rootChildren.get(intent.getKeyword()) != null
									&& this.rootChildren.get(intent.getKeyword()).expectsFile()) {
								currentContext = this.rootChildren.get(intent.getKeyword());
								// get("0") refers to an empty intent that is accessible from the start state
							} else if (this.rootChildren.containsKey("anyFile")) {
								currentContext = this.rootChildren.get("anyFile");
							} else {
								currentContext = this.rootChildren.get("default");
							}
							this.updateConversationState(message.getChannel(), currentContext, conversationId);
							recognizedEntities.put(message.getChannel(), intent.getEntities());
						} else {
							currentContext = this.rootChildren.get(intent.getKeyword());
							if (currentContext == null || currentContext.expectsFile()) {
								if (this.rootChildren.get("0") != null) {
									currentContext = this.rootChildren.get("0");
								} else {
									if (intent.getEntitieValues().size() > 0) {
										currentContext = this.rootChildren
												.get(intent.getEntitieValues().get(0));
										if (currentContext == null) {
											currentContext = this.rootChildren.get("default");
										}
									}

								}
							}
							System.out.println(intent.getKeyword() + " detected with " + intent.getConfidence()
									+ " confidence. for conversation id: " + conversationId.toString());
							this.updateConversationState(message.getChannel(), currentContext, conversationId);
							addEntityToRecognizedList(message.getChannel(), intent.getEntities());
						}
					} else {
						// any is a static forward
						// TODO include entities of intents
						// If there is no next state, stay in the same state
						if (currentContext.getFollowingMessages() == null
								|| currentContext.getFollowingMessages().isEmpty()) {
							System.out.println("no follow up messages");
							currentContext = this.rootChildren.get(intent.getKeyword());
							this.currentNluModel.put(message.getChannel(), "0");
							System.out.println(intent.getKeyword() + " detected with " + intent.getConfidence()
									+ " confidence.");
							this.updateConversationState(message.getChannel(), currentContext, conversationId);
							addEntityToRecognizedList(message.getChannel(), intent.getEntities());
						} else if (currentContext.getFollowingMessages()
								.get(intent.getKeyword()) != null) {
							System.out.println("try follow up message");
							// check if a file was received during a conversation and search for a follow up
							// incoming message which expects a file.
							if (message.getFileBody() != null) {
								if (currentContext.getFollowingMessages().get(intent.getKeyword())
										.expectsFile()) {
									currentContext = currentContext.getFollowingMessages()
											.get(intent.getKeyword());
									this.updateConversationState(message.getChannel(), currentContext, conversationId);
									addEntityToRecognizedList(message.getChannel(), intent.getEntities());
								} else {
									currentContext = checkDefault(currentContext, message);
								}
							} else if (currentContext.getFollowingMessages().get(intent.getKeyword())
									.expectsFile()) {
								currentContext = checkDefault(currentContext, message);
							} else {
								currentContext = currentContext.getFollowingMessages()
										.get(intent.getKeyword());
								this.updateConversationState(message.getChannel(), currentContext, conversationId);
								addEntityToRecognizedList(message.getChannel(), intent.getEntities());
							}
						} else if (intent.getEntitieValues().size() > 0
								&& currentContext.getFollowingMessages()
										.get(intent.getEntitieValues().get(0)) != null) {
							System.out.println("try follow up message with entity");
							// check if a file was received during a conversation and search for a follow up
							// incoming message which expects a file.
							if (message.getFileBody() != null) {
								if (currentContext.getFollowingMessages()
										.get(intent.getEntitieValues().get(0))
										.expectsFile()) {
									currentContext = currentContext.getFollowingMessages()
											.get(intent.getEntitieValues().get(0));
									this.updateConversationState(message.getChannel(), currentContext, conversationId);
									addEntityToRecognizedList(message.getChannel(), intent.getEntities());
								} else {
									currentContext = checkDefault(currentContext, message);
								}
							} else if (currentContext.getFollowingMessages()
									.get(intent.getEntitieValues().get(0))
									.expectsFile()) {
								currentContext = checkDefault(currentContext, message);
							} else {
								currentContext = currentContext.getFollowingMessages()
										.get(intent.getEntitieValues().get(0));
								this.updateConversationState(message.getChannel(), currentContext, conversationId);
								addEntityToRecognizedList(message.getChannel(), intent.getEntities());
							}
						} else {
							// System.out.println("\u001B[33mDebug --- Followups: " +
							// state.getFollowingMessages() + "\u001B[0m");
							// System.out.println("\u001B[33mDebug --- Emptiness: " +
							// state.getFollowingMessages().keySet().isEmpty() + "\u001B[0m");
							// System.out.println("\u001B[33mDebug --- State: " + state.getIntentKeyword() +
							// "\u001B[0m");
							System.out.println(intent.getKeyword() + " not found in state map. Confidence: "
									+ intent.getConfidence() + " confidence.");
							// try any

							if (currentContext.getFollowingMessages().get("any") != null) {
								currentContext = currentContext.getFollowingMessages().get("any");
								this.updateConversationState(message.getChannel(), currentContext, conversationId);
								addEntityToRecognizedList(message.getChannel(), intent.getEntities());
								// In a conversation state, if no fitting intent was found and an empty leadsTo
								// label is found
							} else if (currentContext.getFollowingMessages().get("") != null
									|| currentContext.getFollowingMessages().get("anyFile") != null) {
								if (message.getFileBody() != null) {
									if (currentContext.getFollowingMessages().get("anyFile") != null) {
										currentContext = currentContext.getFollowingMessages()
												.get("anyFile");
										this.updateConversationState(message.getChannel(), currentContext,
												conversationId);
										addEntityToRecognizedList(message.getChannel(), intent.getEntities());
									} else {
										currentContext = this.rootChildren.get("default");
									}

								} else {
									if (currentContext.getFollowingMessages().get("") != null) {
										currentContext = currentContext.getFollowingMessages()
												.get("");
										this.updateConversationState(message.getChannel(), currentContext,
												conversationId);
										addEntityToRecognizedList(message.getChannel(), intent.getEntities());
									} else {
										currentContext = checkDefault(currentContext, message);
									}
								}
							} else if (intent.getEntities().size() > 0
									&& !this.triggeredFunction.containsKey(message.getChannel())) {
								Collection<Entity> entities = intent.getEntities();
								for (Entity e : entities) {
									currentContext = this.rootChildren.get(e.getEntityName());
									// Dont fully understand the point of this, maybe I added it and forgot...
									// Added return for a quick fix, will need to check more in detail
									if (currentContext != null) {
										this.updateConversationState(message.getChannel(), currentContext,
												conversationId);
										return;
									}
								}

							} else {
								currentContext = checkDefault(currentContext, message);
							}
						}
					}
				} else {
					if (currentContext == null) {
						System.out.println(intent.getKeyword() + " not detected with " + intent.getConfidence()
								+ " confidence.");
						currentContext = this.rootChildren.get("default");
					} else if (currentContext.getFollowingMessages().containsKey("")) {
						if (message.getFileBody() != null) {
							if (currentContext.getFollowingMessages().get("").expectsFile()) {
								currentContext = currentContext.getFollowingMessages().get("");
							} else {
								currentContext = checkDefault(currentContext, message);
							}
						} else {
							if (!currentContext.getFollowingMessages().get("").expectsFile()) {
								currentContext = currentContext.getFollowingMessages().get("");
								this.updateConversationState(message.getChannel(), currentContext, conversationId);
								addEntityToRecognizedList(message.getChannel(), intent.getEntities());
							} else {
								currentContext = checkDefault(currentContext, message);
							}
						}
					}
				}

				// check if skip is wished or not
				if (currentContext != null) {
					System.out.println("Getting response for: " + currentContext.intentKeyword);
					if (currentContext.getFollowingMessages().get("skip") != null) {
						currentContext = currentContext.getFollowingMessages().get("skip");
					}

					String response = currentContext.getResponse(random);
					if (currentContext.getTriggeredFunctionId() != ""
							&& currentContext.getTriggeredFunctionId() != null) {
						this.triggeredFunction.put(message.getChannel(),
								currentContext.getTriggeredFunctionId());
						contextOn = true;
					}

					if (currentContext.getNluID() != "") {
						this.currentNluModel.put(message.getChannel(), currentContext.getNluID());
					}
					if (response != null) {
						if (response != "") {
							String split = "";
							// allows users to use linebreaks \n during the modeling for chat responses
							for (int i = 0; i < response.split("\\\\n").length; i++) {
								split += response.split("\\\\n")[i] + " \n ";
							}
							if (split.contains("[") && split.contains("]")) {
								String[] entitySplit1 = split.split("\\[");
								ArrayList<String> entitySplit2 = new ArrayList<String>();
								for (int i = 1; i < entitySplit1.length; i++) {
									entitySplit2.add(entitySplit1[i].split("\\]")[0]);
								}
								for (String entityName : entitySplit2) {
									if (recognizedEntities != null
											&& recognizedEntities.get(message.getChannel()) != null) {
										for (Entity entity : recognizedEntities.get(message.getChannel())) {
											if (entityName.equals(entity.getEntityName())
													&& entity.getValue() != null) {
												String replace = "[" + entity.getEntityName() + "]";
												split = split.replace(replace, entity.getValue());
											}
										}
									}
								}

							}
							// check if message parses buttons or is simple text
							if (currentContext.getType().equals("Interactive Message")) {
								this.chatMediator.sendBlocksMessageToChannel(message.getChannel(), split,
										this.chatMediator.getAuthToken(),
										currentContext.getFollowingMessages(),
										java.util.Optional.empty());
							} else {
								this.chatMediator.sendMessageToChannel(message.getChannel(),
										replaceVariables(message.getChannel(), split),
										currentContext.getFollowingMessages(),
										currentContext.followupMessageType);
							}
							// check whether a file url is attached to the chat response and try to send it
							// to
							// the user
							if (!currentContext.getFileURL().equals("")) {
								String fileName = "";
								try {
									// Replacable variable in url menteeEmail
									String urlEmail = currentContext.getFileURL();
									if (message.getEmail() != null) {
										urlEmail = currentContext.getFileURL().replace("menteeEmail",
												message.getEmail());
									}
									URL url = new URL(urlEmail);
									HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
									// Header for l2p services
									httpConn.addRequestProperty("Authorization", "Basic " + Base64.getEncoder()
											.encodeToString((bot.getName() + ":actingAgent").getBytes()));

									String fieldValue = httpConn.getHeaderField("Content-Disposition");
									if (fieldValue == null || !fieldValue.contains("filename=\"")) {
										fieldValue = "pdf.pdf";
									}
									// parse the file name from the header field
									fileName = "pdf.pdf";
									if (!fieldValue.equals("pdf.pdf")) {
										fileName = fieldValue.substring(fieldValue.indexOf("filename=\"") + 10,
												fieldValue.length() - 1);
									} else {
										// check if name is part of url
										if (urlEmail.contains(".pdf") || urlEmail.contains(".png")
												|| urlEmail.contains(".svg") || urlEmail.contains(".json")
												|| urlEmail.contains(".txt")) {
											fileName = urlEmail.split("/")[urlEmail.split("/").length - 1];
										}
									}
									InputStream in = httpConn.getInputStream();
									FileOutputStream fileOutputStream = new FileOutputStream(fileName);
									int file_size = httpConn.getContentLength();
									if (file_size < 1) {
										file_size = 2048;
									}
									byte dataBuffer[] = new byte[file_size];
									int bytesRead;
									while ((bytesRead = in.read(dataBuffer, 0, file_size)) != -1) {
										fileOutputStream.write(dataBuffer, 0, bytesRead);
									}
									fileOutputStream.close();
									this.chatMediator.sendFileMessageToChannel(message.getChannel(),
											new File(fileName), "");

								} catch (Exception e) {
									System.out.println("Could not extract File for reason " + e);
									e.printStackTrace();
									java.nio.file.Files.deleteIfExists(Paths.get(fileName));
									this.chatMediator.sendMessageToChannel(message.getChannel(),
											currentContext.getErrorMessage(),
											currentContext.getFollowupMessageType());
								}
							}
							if (currentContext.getTriggeredFunctionId() != null) {
								this.triggeredFunction.put(message.getChannel(),
										currentContext.getTriggeredFunctionId());
								contextOn = true;
							}
						} else {
							if (currentContext.getTriggeredFunctionId() != "") {
								this.triggeredFunction.put(message.getChannel(),
										currentContext.getTriggeredFunctionId());
								contextOn = true;
							} else {
								System.out.println("No Bot Action was given to the Response");
							}
						}
					}
					if (this.triggeredFunction.containsKey(message.getChannel())) {
						triggeredFunctionId = this.triggeredFunction.get(message.getChannel());
					} else
						triggeredFunctionId = currentContext.getTriggeredFunctionId();
					// If conversation flow is terminated, reset state
					if (currentContext.getFollowingMessages().isEmpty()) {
						this.stateMap.remove(message.getChannel());
						if (storedSession.containsKey(message.getChannel())
								&& !this.triggeredFunction.containsKey(message.getChannel())) {

							stateMap.put(message.getChannel(), storedSession.get(message.getChannel()));
							storedSession.remove(message.getChannel());
						} else if (storedSession.containsKey(message.getChannel())
								&& this.triggeredFunction.containsKey(message.getChannel())) {
							this.updateConversationState(message.getChannel(), currentContext, conversationId);
						}
						this.recognizedEntities.remove(message.getChannel());
					}
				}

				triggeredFunctionId = this.triggeredFunction.get(message.getChannel());
				contextOn = true;
				if (currentContext == null
						|| !currentContext.getIntentKeyword().contains("defaultX")) {
					this.defaultAnswerCount.put(message.getChannel(), 0);
				}
				messageInfos.add(new MessageInfo(message, intent, triggeredFunctionId, bot.getName(),
						"", contextOn, recognizedEntities.get(message.getChannel()), this.getName(), conversationId));

			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public void setUrl(String Url) throws AuthTokenException {
		this.url = Url;
		if (this.chatMediator instanceof TelegramChatMediator) {
			((TelegramChatMediator) this.chatMediator).settingWebhook(Url);
		}
	}

	public void close() {
		chatMediator.close();
	}

	public String getEntityValue(String channel, String entityName) {
		String val = "";
		PreparedStatement stmt = null;
		Connection conn = null;
		ResultSet rs = null;
		try {

			conn = db.getDataSource().getConnection();
			stmt = conn.prepareStatement("SELECT value FROM attributes WHERE `channel`=? AND `key`=? ORDER BY id DESC");
			stmt.setString(1, channel);
			stmt.setString(2, entityName);
			rs = stmt.executeQuery();
			if (rs.next()) {
				val = rs.getString("value");
				if (val == null) {
					val = "";
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		try {
			if (rs != null)
				rs.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		;
		try {
			if (stmt != null)
				stmt.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		;
		try {
			if (conn != null)
				conn.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return val;

	}

	private void safeEntities(ChatMessage msg, Bot bot, Intent intent) {
		String user = msg.getUser();
		String channel = msg.getChannel();
		String b = bot.getId();
		if (intent.getEntities() == null) {
			return;
		}
		if (intent.getEntitieValues() == null) {
			return;
		}
		intent.getEntities().forEach((entity) -> {
			if (entity.getValue() == null) {
				return;
			}
			String k = entity.getEntityName();
			String v = entity.getValue();
			PreparedStatement stmt = null;
			PreparedStatement stmt2 = null;
			Connection conn = null;
			ResultSet rs = null;
			try {
				conn = db.getDataSource().getConnection();
				stmt = conn.prepareStatement(
						"SELECT id FROM attributes WHERE `bot`=? AND `channel`=? AND `user`=? AND `key`=?");
				stmt.setString(1, b);
				stmt.setString(2, channel);
				stmt.setString(3, user);
				stmt.setString(4, k);
				rs = stmt.executeQuery();
				boolean f = false;
				while (rs.next())
					f = true;
				if (f) {
					// Update
					stmt2 = conn.prepareStatement(
							"UPDATE attributes SET `value`=? WHERE `bot`=? AND `channel`=? AND `user`=? AND `key`=?");
					stmt2.setString(1, v);
					stmt2.setString(2, b);
					stmt2.setString(3, channel);
					stmt2.setString(4, user);
					stmt2.setString(5, k);
					stmt2.executeUpdate();
				} else {
					// Insert
					stmt2 = conn.prepareStatement(
							"INSERT INTO attributes (`bot`, `channel`, `user`, `key`, `value`) VALUES (?,?,?,?,?)");
					stmt2.setString(1, b);
					stmt2.setString(2, channel);
					stmt2.setString(3, user);
					stmt2.setString(4, k);
					stmt2.setString(5, v);
					stmt2.executeUpdate();
				}
			} catch (SQLException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
				try {
					stmt2.close();
					stmt2 = conn.prepareStatement(
							"INSERT INTO attributes (`bot`, `channel`, `user`, `key`, `value`) VALUES (?,?,?,?,?)");
					stmt2.setString(1, b);
					stmt2.setString(2, channel);
					stmt2.setString(3, user);
					stmt2.setString(4, k);
					stmt2.setString(5, v);
					stmt2.executeUpdate();
				} catch (SQLException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			} finally {
				try {
					if (rs != null)
						rs.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
				;
				try {
					if (stmt != null)
						stmt.close();
					if (stmt2 != null)
						stmt2.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
				;
				try {
					if (conn != null)
						conn.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
				;
			}
		});
	}

	private Intent determineIntent(ChatMessage message, Bot bot) {
		Intent intent = null;

		// Special case: `!` commands
		if (message.getText().startsWith("!")) {

			// Split at first occurring whitespace

			String splitMessage[] = message.getText().split("\\s+", 2);
			// First word without '!' prefix
			String intentKeyword = splitMessage[0].substring(1);
			IncomingMessage incMsg = this.rootChildren.get(intentKeyword);
			// TODO: Log this? (`!` command with unknown intent / keyword)
			if (incMsg == null && !intentKeyword.toLowerCase().equals("exit")) {
				if (this.currentNluModel.get(message.getChannel()) == "0") {
					return null;
				} else {
					ArrayList<String> empty = new ArrayList<String>();
					empty.add("");
					incMsg = new IncomingMessage(intentKeyword, "", false, empty, null, "", null, "", "text");
					if (splitMessage.length > 1) {
						incMsg.setEntityKeyword(incMsg.getIntentKeyword());
					} else {
						incMsg.setEntityKeyword("newEntity");
					}

				}
			}
			if (splitMessage.length > 1) {
				incMsg.setEntityKeyword(incMsg.getIntentKeyword());
			} else {
				incMsg.setEntityKeyword("newEntity");
			}
			String entityKeyword = incMsg.getEntityKeyword();
			String entityValue = null;
			// Entity value is the rest of the message. The whole rest
			// is in the second element, since we only split it into two parts.
			if (splitMessage.length > 1) {
				entityValue = splitMessage[1];
			}

			intent = new Intent(intentKeyword, entityKeyword, entityValue);
		} else {
			if (bot.getRasaServer(currentNluModel.get(message.getChannel())) != null) {
				intent = bot.getRasaServer(currentNluModel.get(message.getChannel()))
						.getIntent(Intent.replaceUmlaute(message.getText()));
			} else {
				// if the given id is not fit to any server, pick the first one. (In case
				// someone specifies only
				// one server and does not give an ID)
				intent = bot.getFirstRasaServer().getIntent(Intent.replaceUmlaute(message.getText()));
			}

		}
		return intent;
	}

	/**
	 * Stores the currentContext in the session for the given channel.
	 * 
	 * @param channelId      The channel id for which the context should be stored.
	 * @param currentContext The context to store.
	 */
	private void storeContextInSession(String channelId, IncomingMessage currentContext) {
		if (currentContext != null) {
			this.stateMap.put(channelId, currentContext);
		}
	}

	/**
	 * Updates the state of the conversation for the given channel.
	 * Also sets the conversation id for the given state.
	 * 
	 * @param channelId      The channel id for which the state should be updated.
	 * @param state          The new state of the conversation.
	 * @param conversationId The conversation id to set.
	 */
	private void updateConversationState(String channelId, IncomingMessage state, UUID conversationId) {
		state.setConversationId(conversationId);
		this.stateMap.put(channelId, state);
	}
}
package i5.las2peer.services.socialBotManagerService.chat;

public class ChatMessage {
	private String channel;
	private String user;
	private String text;
	
	public ChatMessage(String channel, String user, String text) {
		this.channel = channel;
		this.user = user;
		this.text = text;
	}
	
	public void setText(String text) {
		this.text = text;
	}
	
	public String getChannel() {
		return this.channel;
	}
	
	public String getUser() {
		return this.user;
	}
	
	public String getText() {
		return this.text;
	}
}

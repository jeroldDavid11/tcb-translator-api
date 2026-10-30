package com.tcb.sms.services;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service that sends WhatsApp messages using Twilio.
 * Uses your Twilio WhatsApp sandbox or approved WhatsApp number.
 */
@Service
public class WhatsAppNotificationService {

    @Value("${app.twilio.accountSid}")
    private String accountSid;

    @Value("${app.twilio.authToken}")
    private String authToken;

    /**
     * Twilio WhatsApp "from" address, e.g. "whatsapp:+14155238886"
     * For sandbox, see Twilio Console → WhatsApp Sandbox.
     */
    @Value("${app.twilio.whatsappFrom}")
    private String whatsappFrom;

    private boolean initialized = false;

    private void initIfNeeded() {
        if (!initialized) {
            Twilio.init(accountSid, authToken);
            initialized = true;
        }
    }

    /**
     * Sends a WhatsApp message.
     *
     * @param toE164 Phone number in E.164 format, e.g. "+639XXXXXXXXX"
     * @param body   Text message body
     * @return Twilio Message SID
     */
    public String sendWhatsApp(String toE164, String body) {
        initIfNeeded();

        // Twilio WhatsApp requires the "whatsapp:" prefix on both From and To.
        String to = normalizeWhatsAppTo(toE164);

        Message message = Message.creator(
                new PhoneNumber(to),           // To: e.g. "whatsapp:+63908..."
                new PhoneNumber(whatsappFrom), // From: e.g. "whatsapp:+14155238886"
                body
        ).create();

        return message.getSid();
    }

    /**
     * If the input already starts with "whatsapp:", leave it as-is.
     * Otherwise, add "whatsapp:" before the E.164 number.
     */
    private String normalizeWhatsAppTo(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("WhatsApp 'to' number is null");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("whatsapp:")) {
            return trimmed;
        }
        return "whatsapp:" + trimmed;
    }
}
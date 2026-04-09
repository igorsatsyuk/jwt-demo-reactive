package lt.satsyuk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageSource messageSource;

    public String getMessage(String code) {
        return getMessage(code, null, Locale.ENGLISH);
    }

    public String getMessage(String code, Object[] args) {
        return getMessage(code, args, Locale.ENGLISH);
    }

    public String getMessage(String code, Object[] args, Locale locale) {
        String pattern = messageSource.getMessage(code, null, locale == null ? Locale.ENGLISH : locale);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return MessageFormat.format(pattern, args);
    }
}


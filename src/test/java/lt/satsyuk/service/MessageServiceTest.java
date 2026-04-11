package lt.satsyuk.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageServiceTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private final MessageService messageService = new MessageService(messageSource);

    @Test
    void getMessage_returnsPatternWithoutFormattingWhenNoArgsProvided() {
        when(messageSource.getMessage("error.validation.failed", null, Locale.ENGLISH))
                .thenReturn("Validation failed");

        String plain = messageService.getMessage("error.validation.failed");

        assertThat(plain).isEqualTo("Validation failed");
    }

    @Test
    void getMessage_formatsMessageWithArgsAndUsesEnglishForNullLocale() {
        when(messageSource.getMessage("error.account.notFound", null, Locale.ENGLISH))
                .thenReturn("Account {0} not found");

        String formatted = messageService.getMessage("error.account.notFound", new Object[]{42L}, null);

        assertThat(formatted).isEqualTo("Account 42 not found");
    }
}


package lt.satsyuk.mapper;

import lt.satsyuk.dto.AccountResponse;
import lt.satsyuk.model.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(target = "accountId", source = "id")
    AccountResponse toResponse(Account account);
}


package org.xianshen.mumirrorb.pojo.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import java.util.UUID;

@Data
public class ModelConnectionTestDTO {
    private UUID profileId;
    private boolean useCurrentKey;
    @NotBlank @Size(max = 50)
    private String aiProvider;
    @NotBlank @Pattern(regexp = "openai|anthropic")
    private String aiProtocol;
    @NotBlank @Size(max = 2048)
    private String aiBaseUrl;
    @NotBlank @Size(max = 100)
    private String aiModel;
    @Size(max = 16384) @ToString.Exclude
    private String aiApiKey;
}

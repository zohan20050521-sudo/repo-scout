package io.github.chada010.reposcout.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.chada010.reposcout.exception.InvalidParamException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoAddressParserTest {

    @ParameterizedTest
    @CsvSource({
            "octocat/Hello-World,                                octocat, Hello-World",
            "my-org/repo_name.js,                                my-org,  repo_name.js",
            "https://github.com/octocat/Hello-World,             octocat, Hello-World",
            "https://github.com/octocat/Hello-World/,            octocat, Hello-World",
            "https://github.com/octocat/Hello-World.git,         octocat, Hello-World",
            "https://github.com/octocat/Hello-World.git/,        octocat, Hello-World",
    })
    void validInputsAreParsed(String input, String owner, String name) {
        RepoAddressParser.RepoAddress address = RepoAddressParser.parse(input);

        assertThat(address.owner()).isEqualTo(owner);
        assertThat(address.name()).isEqualTo(name);
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        RepoAddressParser.RepoAddress address = RepoAddressParser.parse("  octocat/Hello-World  ");

        assertThat(address.owner()).isEqualTo("octocat");
        assertThat(address.name()).isEqualTo("Hello-World");
    }

    @Test
    void caseIsKeptAsInputForGithubToNormalize() {
        RepoAddressParser.RepoAddress address = RepoAddressParser.parse("OCTOCAT/hello-world");

        assertThat(address.owner()).isEqualTo("OCTOCAT");
        assertThat(address.name()).isEqualTo("hello-world");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankInputIsRejected(String input) {
        assertThatThrownBy(() -> RepoAddressParser.parse(input))
                .isInstanceOf(InvalidParamException.class)
                .hasMessageContaining("不能为空");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "foo",
            "a/b/c",
            "owner/",
            "/repo",
            "owner/..",
            "owner/.",
            "a b/c",
            "a_b/c",
            "http://github.com/a/b",
            "HTTPS://github.com/a/b",
            "git@github.com:a/b.git",
            "git@github.com/a/b",
            "https://www.github.com/a/b",
            "https://gitlab.com/a/b",
            "https://github.com/a",
            "https://github.com/a/b/tree/main",
    })
    void invalidInputsAreRejectedWith400Semantics(String input) {
        assertThatThrownBy(() -> RepoAddressParser.parse(input))
                .isInstanceOf(InvalidParamException.class)
                .hasMessageContaining("格式不合法");
    }
}

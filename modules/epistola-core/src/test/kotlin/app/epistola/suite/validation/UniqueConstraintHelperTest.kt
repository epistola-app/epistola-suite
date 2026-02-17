package app.epistola.suite.validation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import org.junit.jupiter.api.Test
import java.sql.SQLException

class UniqueConstraintHelperTest {

    @Test
    fun `returns result when no exception`() {
        val result = executeOrThrowDuplicate("environment", "test-id") {
            "success"
        }
        assertThat(result).isEqualTo("success")
    }

    @Test
    fun `throws DuplicateIdException on unique violation`() {
        val sqlException = SQLException("duplicate key value violates unique constraint", "23505")
        val jdbiException = UnableToExecuteStatementException(sqlException, null)

        assertThatThrownBy {
            executeOrThrowDuplicate("environment", "test-env") {
                throw jdbiException
            }
        }
            .isInstanceOf(DuplicateIdException::class.java)
            .hasMessageContaining("test-env")
            .extracting("entityType", "id")
            .containsExactly("environment", "test-env")
    }

    @Test
    fun `re-throws other JDBI exceptions`() {
        val sqlException = SQLException("some other error", "42000")
        val jdbiException = UnableToExecuteStatementException(sqlException, null)

        assertThatThrownBy {
            executeOrThrowDuplicate("environment", "test-env") {
                throw jdbiException
            }
        }.isInstanceOf(UnableToExecuteStatementException::class.java)
    }

    @Test
    fun `re-throws non-JDBI exceptions`() {
        assertThatThrownBy {
            executeOrThrowDuplicate("environment", "test-env") {
                throw IllegalStateException("something else")
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `detects unique violation in nested cause chain`() {
        val sqlException = SQLException("duplicate key", "23505")
        val wrappedException = RuntimeException("wrapper", sqlException)
        val jdbiException = UnableToExecuteStatementException(wrappedException, null)

        assertThatThrownBy {
            executeOrThrowDuplicate("theme", "my-theme") {
                throw jdbiException
            }
        }.isInstanceOf(DuplicateIdException::class.java)
    }
}

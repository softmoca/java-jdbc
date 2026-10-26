package com.techcourse.service;

import com.techcourse.domain.User;
import com.interface21.dao.DataAccessException;
import com.interface21.jdbc.datasource.DataSourceUtils;
import com.interface21.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class TxUserService implements UserService {

    private final UserService userService;
    private final DataSource dataSource;

    public TxUserService(final UserService userService,
                         final DataSource dataSource) {
        this.userService = userService;
        this.dataSource = dataSource;
    }

    @Override
    public User findById(final long id) {
        return userService.findById(id);
    }

    @Override
    public void save(final User user) {
        userService.save(user);
    }

    @Override
    public void changePassword(final long id,
                               final String newPassword,
                               final String createdBy) {

        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean bound = false;
        Throwable originalFailure = null;

        try {
            connection.setAutoCommit(false);

            TransactionSynchronizationManager.bindResource(dataSource, connection);
            bound = true;

            try {
                userService.changePassword(id, newPassword, createdBy);
                connection.commit();

            } catch (SQLException e) {
                DataAccessException failure = new DataAccessException(e);
                rollback(connection, failure);
                throw failure;

            } catch (RuntimeException | Error e) {
                rollback(connection, e);
                throw e;
            }

        } catch (SQLException e) {
            DataAccessException failure = new DataAccessException(e);
            originalFailure = failure;
            throw failure;

        } catch (RuntimeException | Error e) {
            originalFailure = e;
            throw e;

        } finally {
            releaseConnection(connection, bound, originalFailure);
        }
    }

    private void releaseConnection(
            Connection connection,
            boolean bound,
            Throwable originalFailure) {

        Throwable cleanupFailure = null;

        try {
            if (bound) {
                TransactionSynchronizationManager.unbindResource(dataSource);
            }
        } catch (RuntimeException | Error e) {
            cleanupFailure = e;
        }

        try {
            DataSourceUtils.releaseConnection(connection, dataSource);
        } catch (RuntimeException | Error e) {
            if (cleanupFailure == null) {
                cleanupFailure = e;
            } else if (cleanupFailure != e) {
                cleanupFailure.addSuppressed(e);
            }
        }

        if (cleanupFailure == null) {
            return;
        }

        if (originalFailure != null) {
            if (originalFailure != cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        } else if (cleanupFailure instanceof RuntimeException e) {
            throw e;
        } else {
            throw (Error) cleanupFailure;
        }
    }

    private void rollback(Connection connection, Throwable originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }
}

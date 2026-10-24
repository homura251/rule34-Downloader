package com.homura251.rule34downloader.data

import android.content.Context
import com.homura251.rule34downloader.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidAccountRepository(context: Context, scope: CoroutineScope) : AccountRepository {
    private val store = CredentialsStore(context)
    private val status = context.getSharedPreferences("account_status", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(AccountState(AccountStatus.LOADING))
    override val state = mutable.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            lock.withLock { publish() }
        }
    }
    private fun publish() {
        val saved = store.get()
        mutable.value = when {
            saved == null -> AccountState()
            status.getBoolean("invalid", false) -> AccountState(AccountStatus.INVALID, saved.userId, "API 凭据已失效，请在设置中更新。")
            else -> AccountState(AccountStatus.READY, saved.userId)
        }
    }
    private suspend fun ready() { state.first { it.status != AccountStatus.LOADING } }
    override suspend fun credentials(): ApiCredentials? {
        ready()
        return withContext(Dispatchers.IO) { lock.withLock { store.get() } }
    }
    override suspend fun save(credentials: ApiCredentials) {
        ready()
        withContext(Dispatchers.IO) { lock.withLock {
            store.save(credentials); status.edit().putBoolean("invalid", false).commit(); publish()
        } }
    }
    override suspend fun clear() {
        ready()
        withContext(Dispatchers.IO) { lock.withLock { store.clear(); status.edit().clear().commit(); publish() } }
    }
    override suspend fun invalidate(expected: ApiCredentials, reason: String) {
        ready()
        withContext(Dispatchers.IO) { lock.withLock {
            if (store.get() == expected) {
                status.edit().putBoolean("invalid", true).commit()
                mutable.value = AccountState(AccountStatus.INVALID, expected.userId, reason)
            }
        } }
    }
}

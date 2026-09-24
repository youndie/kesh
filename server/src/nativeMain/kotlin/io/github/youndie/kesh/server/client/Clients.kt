package io.github.youndie.kesh.server.client

/**
 * Every connected client, by id. Store thread only (research D-14), so registration, `CLIENT LIST`
 * and `CLIENT KILL` see one consistent set without a lock.
 *
 * The connection ceiling lives here: a client that would make [maxClients] + 1 is not registered, and
 * the caller refuses it the way Redis does.
 */
class Clients(
    val maxClients: Int,
    private val passwordRequired: Boolean,
) {
    private val byId = LinkedHashMap<Long, ClientState>()
    private var nextId = 1L

    /** Connections refused at the ceiling — Redis's `rejected_connections`. */
    var rejected: Long = 0
        private set

    val size: Int get() = byId.size

    fun register(
        address: String,
        localAddress: String,
        closeSocket: () -> Unit,
    ): ClientState? {
        if (byId.size >= maxClients) {
            rejected++
            return null
        }
        val client = ClientState(nextId++, address, localAddress, closeSocket, authenticated = !passwordRequired)
        byId[client.id] = client
        return client
    }

    fun unregister(client: ClientState) {
        byId.remove(client.id)
    }

    fun all(): Collection<ClientState> = byId.values
}

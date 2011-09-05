package org.elasticmq.storage.squeryl

import org.squeryl._
import internals.DatabaseAdapter
import PrimitiveTypeMode._
import org.elasticmq.storage.{MessageStorage, QueueStorage, Storage}
import org.elasticmq._
import java.lang.IllegalArgumentException
import com.mchange.v2.c3p0.ComboPooledDataSource

class SquerylStorage extends Storage {
  def messageStorage = new SquerylMessageStorage
  def queueStorage = new SquerylQueueStorage
}

object SquerylStorage {
  def initialize(dbAdapter: DatabaseAdapter, jdbcURL: String, driverClass: String,
                 credentials: Option[(String, String)] = None,
                 create: Boolean = true) {
    import org.squeryl.SessionFactory

    val cpds = new ComboPooledDataSource
    cpds.setDriverClass(driverClass)
    cpds.setJdbcUrl(jdbcURL)

    credentials match {
      case Some((username, password)) => {
        cpds.setUser(username)
        cpds.setPassword(password)
      }
      case _ =>
    }

    SessionFactory.concreteFactory = Some(() => Session.create(cpds.getConnection, dbAdapter))

    // TODO: do in a nicer way
    if (create) {
      transaction {
        try {
          MQSchema.create
        } catch {
          case e: Exception if e.getMessage.contains("already exists") => // do nothing
          case e => throw e
        }
      }
    }
  }

  def shutdown(drop: Boolean) {
    if (drop) {
      transaction {
        MQSchema.drop
      }
    }
  }
}

class SquerylQueueStorage extends QueueStorage {
  import MQSchema._

  def persistQueue(queue: Queue) {
    transaction {
      queues.insert(SquerylQueue.from(queue))
    }
  }

  def updateQueue(queue: Queue) {
    transaction {
      queues.update(SquerylQueue.from(queue))
    }
  }

  def deleteQueue(queue: Queue) {
    transaction {
      queues.delete(queue.name)
    }
  }

  def lookupQueue(name: String) = {
    transaction {
      queues.lookup(name).map(_.toQueue)
    }
  }

  def listQueues: Seq[Queue] = {
    transaction {
      from(queues)(q => select(q)).map(_.toQueue).toSeq
    }
  }
}

class SquerylMessageStorage extends MessageStorage {
  import MQSchema._

  def persistMessage(message: Message) {
    transaction {
      messages.insert(SquerylMessage.from(message))
    }
  }

  def updateMessage(message: Message) {
    transaction {
      messages.update(SquerylMessage.from(message))
    }
  }

  def updateLastDelivered(message: Message, lastDelivered: Long) = {
    transaction {
      val updatedCount = update(messages)(m =>
        where(m.id === message.id and m.lastDelivered === message.lastDelivered)
                set(m.lastDelivered := lastDelivered))

      if (updatedCount == 0) None else Some(message.copy(lastDelivered = lastDelivered))
    }
  }

  def deleteMessage(message: Message) {
    transaction {
      messages.delete(message.id)
    }
  }

  def lookupMessage(id: String) = {
    transaction {
      from(messages, queues)((m, q) => where(m.id === id and queuesToMessagesCond(m, q)) select(m, q))
              .headOption.map { case (m, q) => m.toMessage(q) }
    }
  }

  def lookupPendingMessage(queue: Queue, deliveryTime: Long) = {
    transaction {
      from(messages, queues)((m, q) =>
        where(m.queueName === queue.name and
                queuesToMessagesCond(m, q) and
                ((m.lastDelivered plus m.visibilityTimeout) lte deliveryTime)) select(m, q))
              .page(0, 1).headOption.map { case (m, q) => m.toMessage(q) }
    }
  }
}

private [squeryl] object MQSchema extends Schema {
  def queuesToMessagesCond(m: SquerylMessage, q: SquerylQueue) = q.id === m.queueName

  val queues = table[SquerylQueue]
  val messages = table[SquerylMessage]

  val queuesToMessages = oneToManyRelation(queues, messages).via((q, m) => queuesToMessagesCond(m, q))
  queuesToMessages.foreignKeyDeclaration.constrainReference(onDelete cascade)
}

private[squeryl] class SquerylQueue(val id: String, val defaultVisibilityTimeout: Long) extends KeyedEntity[String] {
  def toQueue = Queue(id, MillisVisibilityTimeout(defaultVisibilityTimeout))
}

private[squeryl] object SquerylQueue {
  def from(queue: Queue) = new SquerylQueue(queue.name, queue.defaultVisibilityTimeout.millis)
}

private[squeryl] class SquerylMessage(val id: String, val queueName: String, val content: String,
                                      val visibilityTimeout: Long, val lastDelivered: Long) extends KeyedEntity[String] {
  def toMessage(q: SquerylQueue) = Message(q.toQueue, id, content,
    MillisVisibilityTimeout(visibilityTimeout), lastDelivered)
}

private[squeryl] object SquerylMessage {
  def from(message: Message) = {
    new SquerylMessage(message.id, message.queue.name, message.content,
    message.visibilityTimeout match {
      case DefaultVisibilityTimeout => throw new IllegalArgumentException("Persisted messages must have a non-default visibility timeout")
      case MillisVisibilityTimeout(millis) => millis
    },
    message.lastDelivered)
  }
}

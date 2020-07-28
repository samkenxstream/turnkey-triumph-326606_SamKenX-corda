package net.corda.bn.demo.workflows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.demo.contracts.LoanContract
import net.corda.bn.demo.contracts.LoanState
import net.corda.bn.flows.DatabaseService
import net.corda.core.contracts.ReferencedStateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
@StartableByRPC
class SettleLoanFlow(private val loanId: UniqueIdentifier, private val amountToSettle: Int) : BusinessNetworkIntegrationFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.LinearStateQueryCriteria(linearId = listOf(loanId)))
        val inputState = serviceHub.vaultService.queryBy(LoanState::class.java, criteria).states.single()

        if (ourIdentity != inputState.state.data.borrower) {
            throw FlowException("Only borrower can settle loan")
        }
        if (amountToSettle <= 0) {
            throw FlowException("Settlement can only be done with positive amount")
        }
        if (inputState.state.data.amount - amountToSettle < 0) {
            throw FlowException("Amount to settle is bigger than actual loan amount")
        }

        val (lenderMembership, borrowerMembership) = inputState.state.data.run {
            businessNetworkPartialVerification(networkId, lender, borrower)
        }

        val isFullySettled = inputState.state.data.amount - amountToSettle == 0
        val command = if (isFullySettled) LoanContract.Commands.Exit() else LoanContract.Commands.Settle()
        val builder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(inputState)
                .addCommand(command, inputState.state.data.participants.map { it.owningKey })
                .addReferenceState(ReferencedStateAndRef(lenderMembership))
                .addReferenceState(ReferencedStateAndRef(borrowerMembership))
        if (!isFullySettled) {
            builder.addOutputState(inputState.state.data.settle(amountToSettle))
        }
        builder.verify(serviceHub)

        val selfSignedTransaction = serviceHub.signInitialTransaction(builder)
        val sessions = listOf(initiateFlow(inputState.state.data.lender))
        val fullSignedTransaction = subFlow(CollectSignaturesFlow(selfSignedTransaction, sessions))
        return subFlow(FinalityFlow(fullSignedTransaction, sessions))
    }
}

@InitiatedBy(SettleLoanFlow::class)
class SettleLoanResponderFlow(private val session: FlowSession) : BusinessNetworkIntegrationFlow<Unit>() {

    @Suspendable
    override fun call() {
        val signResponder = object : SignTransactionFlow(session) {
            override fun checkTransaction(stx: SignedTransaction) {
                val command = stx.tx.commands.single()
                if (command.value !is LoanContract.Commands.Settle && command.value !is LoanContract.Commands.Exit) {
                    throw FlowException("Only LoanContract.Commands.Settle or LoanContract.Commands.Exit commands are allowed")
                }

                val loanState = stx.tx.outputStates.single() as LoanState
                loanState.apply {
                    if (lender != ourIdentity) {
                        throw FlowException("Lender doesn't match receivers's identity")
                    }
                    if (borrower != session.counterparty) {
                        throw FlowException("Borrower doesn't match senders's identity")
                    }
                    businessNetworkPartialVerification(networkId, lender, borrower)
                }
            }
        }
        val stx = subFlow(signResponder)

        subFlow(ReceiveFinalityFlow(session, stx.id))
    }
}
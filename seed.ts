/**
 * public-fund seed — writes baseline public fund records into the PDS so
 * world coverage can count real `com.etzhayyim.apps.publicFund.*` collections.
 *
 * Usage: npx tsx 60-apps/etzhayyim-project-public-fund/seed.ts
 */

declare const process: {
  env: Record<string, string | undefined>;
  exitCode?: number;
};

const PDS = 'https://atproto.etzhayyim.com';
const ROOT_DID = 'did:web:public-fund.etzhayyim.com';
const PROJECT_ID = 'public-fund';

const etzhayyim_TOKEN = process.env.etzhayyim_TOKEN;
if (!etzhayyim_TOKEN) {
  throw new Error('etzhayyim_TOKEN env var required — run `export etzhayyim_TOKEN=$(etzhayyim auth token)` first');
}

const INTERNAL_HEADERS = {
  'Content-Type': 'application/json',
  'Authorization': `Bearer ${etzhayyim_TOKEN}`,
  'x-etzhayyim-org-id': 'anon',
};

async function createRecord(collection: string, record: Record<string, unknown>): Promise<void> {
  const res = await fetch(`${PDS}/xrpc/com.atproto.repo.createRecord`, {
    method: 'POST',
    headers: INTERNAL_HEADERS,
    body: JSON.stringify({ repo: ROOT_DID, collection, record }),
  });
  if (!res.ok) {
    throw new Error(`createRecord ${collection}: ${res.status} ${await res.text()}`);
  }
}

async function actorCreate(did: string, displayName: string, description: string): Promise<void> {
  const res = await fetch(`${PDS}/xrpc/com.etzhayyim.actor.create`, {
    method: 'POST',
    headers: INTERNAL_HEADERS,
    body: JSON.stringify({ did, projectId: PROJECT_ID, displayName, description, hasWorker: false }),
  });
  if (!res.ok) {
    console.warn(`actor.create ${did}: ${res.status} ${await res.text()}`);
  }
}

function isoNow(): string {
  return new Date().toISOString();
}

const FUND_PROGRAMS = [
  {
    programId: 'pf-common-fund',
    name: 'Common Public Fund',
    cofogCode: '01.8',
    budgetCredits: 5000000,
    destinationId: 'public-fund:common',
    status: 'active',
    executionStart: '2026-01-01',
    executionEnd: '2026-12-31',
    summary: 'Default common fund for general-purpose public funding and routed allocations.',
  },
  {
    programId: 'pf-education-family',
    name: 'Education and Family Support Fund',
    cofogCode: '09.6',
    budgetCredits: 2400000,
    destinationId: 'public-fund:education-family',
    status: 'active',
    executionStart: '2026-01-01',
    executionEnd: '2026-12-31',
    summary: 'Supports after-school learning, early childhood programs, and family resilience.',
  },
  {
    programId: 'pf-health-access',
    name: 'Health Access Fund',
    cofogCode: '07.3',
    budgetCredits: 1800000,
    destinationId: 'public-fund:health-access',
    status: 'active',
    executionStart: '2026-01-01',
    executionEnd: '2026-12-31',
    summary: 'Expands preventive care access, local clinics, and medication support.',
  },
  {
    programId: 'pf-climate-resilience',
    name: 'Climate Resilience Fund',
    cofogCode: '05.3',
    budgetCredits: 2100000,
    destinationId: 'public-fund:climate-resilience',
    status: 'active',
    executionStart: '2026-01-01',
    executionEnd: '2026-12-31',
    summary: 'Funds local adaptation, flood prevention, and cooling infrastructure.',
  },
];

const FUND_CAMPAIGNS = [
  {
    campaignId: 'pfc-school-meals-2026',
    programId: 'pf-education-family',
    title: 'School Meal Access 2026',
    goalCredits: 600000,
    pledgedCredits: 182000,
    deadline: '2026-06-30',
    visibility: 'public',
    status: 'open',
  },
  {
    campaignId: 'pfc-rural-clinics-2026',
    programId: 'pf-health-access',
    title: 'Rural Clinic Continuity',
    goalCredits: 450000,
    pledgedCredits: 204500,
    deadline: '2026-07-15',
    visibility: 'public',
    status: 'open',
  },
  {
    campaignId: 'pfc-heat-shelters-2026',
    programId: 'pf-climate-resilience',
    title: 'Neighborhood Heat Shelter Network',
    goalCredits: 520000,
    pledgedCredits: 264000,
    deadline: '2026-08-15',
    visibility: 'public',
    status: 'open',
  },
];

const PLEDGES = [
  { pledgeId: 'plg-001', campaignId: 'pfc-school-meals-2026', supporterDid: 'did:web:user-alice.etzhayyim.com', amountCredits: 25000, ledgerTxId: 'credits-tx-pledge-001' },
  { pledgeId: 'plg-002', campaignId: 'pfc-rural-clinics-2026', supporterDid: 'did:web:user-bob.etzhayyim.com', amountCredits: 40000, ledgerTxId: 'credits-tx-pledge-002' },
  { pledgeId: 'plg-003', campaignId: 'pfc-heat-shelters-2026', supporterDid: 'did:web:user-caro.etzhayyim.com', amountCredits: 30000, ledgerTxId: 'credits-tx-pledge-003' },
];

const ROUTED_ALLOCATIONS = [
  { allocationId: 'ra-001', destinationId: 'public-fund:common', sourceSpendTxId: 'credits-spend-1001', publicFundAmount: 120, publicFundBps: 1000 },
  { allocationId: 'ra-002', destinationId: 'public-fund:education-family', sourceSpendTxId: 'credits-spend-1002', publicFundAmount: 250, publicFundBps: 1000 },
  { allocationId: 'ra-003', destinationId: 'public-fund:health-access', sourceSpendTxId: 'credits-spend-1003', publicFundAmount: 180, publicFundBps: 1000 },
  { allocationId: 'ra-004', destinationId: 'public-fund:climate-resilience', sourceSpendTxId: 'credits-spend-1004', publicFundAmount: 310, publicFundBps: 1000 },
];

const ELIGIBILITY_POLICIES = [
  {
    policyId: 'ep-edu-001',
    programId: 'pf-education-family',
    policyName: 'Low-income student support',
    isicCodes: ['P8510', 'P8521'],
    geoScope: ['jpn-13', 'jpn-27'],
    apqcStage: 'application-review',
  },
  {
    policyId: 'ep-health-001',
    programId: 'pf-health-access',
    policyName: 'Primary care underserved areas',
    isicCodes: ['Q8610', 'Q8620'],
    geoScope: ['usa-az', 'usa-nm'],
    apqcStage: 'eligibility-check',
  },
  {
    policyId: 'ep-climate-001',
    programId: 'pf-climate-resilience',
    policyName: 'Heat and flood adaptation projects',
    isicCodes: ['F4220', 'E3900'],
    geoScope: ['ind-mh', 'phl-00'],
    apqcStage: 'technical-review',
  },
];

const APPLICATIONS = [
  {
    applicationId: 'app-edu-001',
    programId: 'pf-education-family',
    applicantDid: 'did:web:npo-learning-hub.etzhayyim.com',
    applicantName: 'Learning Hub NPO',
    requestedCredits: 90000,
    status: 'submitted',
  },
  {
    applicationId: 'app-health-001',
    programId: 'pf-health-access',
    applicantDid: 'did:web:rural-clinic-net.etzhayyim.com',
    applicantName: 'Rural Clinic Network',
    requestedCredits: 120000,
    status: 'approved',
  },
  {
    applicationId: 'app-climate-001',
    programId: 'pf-climate-resilience',
    applicantDid: 'did:web:cool-roof-lab.etzhayyim.com',
    applicantName: 'Cool Roof Lab',
    requestedCredits: 150000,
    status: 'under_review',
  },
];

const DECISIONS = [
  {
    decisionId: 'dec-health-001',
    applicationId: 'app-health-001',
    reviewerDid: 'did:web:public-fund.etzhayyim.com:reviewer:health',
    result: 'approve',
    reason: 'Clinic network meets underserved-area and continuity criteria.',
  },
  {
    decisionId: 'dec-edu-001',
    applicationId: 'app-edu-001',
    reviewerDid: 'did:web:public-fund.etzhayyim.com:reviewer:education',
    result: 'hold',
    reason: 'Awaiting latest beneficiary roster and school-partner verification.',
  },
];

const DISBURSEMENTS = [
  {
    disbursementId: 'dis-health-001',
    applicationId: 'app-health-001',
    decisionId: 'dec-health-001',
    amountCredits: 120000,
    currency: 'GCC',
    status: 'executed',
    auditId: 'audit-dis-health-001',
    ledgerTxId: 'credits-tx-disburse-001',
  },
];

async function main(): Promise<void> {
  console.log('=== Public Fund Seed ===');
  await actorCreate(ROOT_DID, 'Public Fund', 'Public fund program, application, and disbursement registry');

  for (const program of FUND_PROGRAMS) {
    await createRecord('com.etzhayyim.apps.publicFund.fundProgram', {
      ...program,
      ownerDid: ROOT_DID,
      createdAt: isoNow(),
      updatedAt: isoNow(),
    });
  }

  for (const campaign of FUND_CAMPAIGNS) {
    await createRecord('com.etzhayyim.apps.publicFund.fundCampaign', {
      ...campaign,
      ownerDid: ROOT_DID,
      createdAt: isoNow(),
      updatedAt: isoNow(),
    });
  }

  for (const pledge of PLEDGES) {
    await createRecord('com.etzhayyim.apps.publicFund.pledge', {
      ...pledge,
      ownerDid: ROOT_DID,
      createdAt: isoNow(),
    });
  }

  for (const allocation of ROUTED_ALLOCATIONS) {
    await createRecord('com.etzhayyim.apps.publicFund.routedAllocation', {
      ...allocation,
      ownerDid: ROOT_DID,
      createdAt: isoNow(),
    });
  }

  for (const policy of ELIGIBILITY_POLICIES) {
    await createRecord('com.etzhayyim.apps.publicFund.eligibilityPolicy', {
      ...policy,
      ownerDid: ROOT_DID,
      status: 'published',
      createdAt: isoNow(),
    });
  }

  for (const application of APPLICATIONS) {
    await createRecord('com.etzhayyim.apps.publicFund.application', {
      ...application,
      ownerDid: ROOT_DID,
      submittedAt: isoNow(),
    });
  }

  for (const decision of DECISIONS) {
    await createRecord('com.etzhayyim.apps.publicFund.decision', {
      ...decision,
      ownerDid: ROOT_DID,
      decidedAt: isoNow(),
    });
  }

  for (const disbursement of DISBURSEMENTS) {
    await createRecord('com.etzhayyim.apps.publicFund.disbursement', {
      ...disbursement,
      ownerDid: ROOT_DID,
      executedAt: isoNow(),
    });
  }

  console.log(
    `Seeded ${FUND_PROGRAMS.length + FUND_CAMPAIGNS.length + PLEDGES.length + ROUTED_ALLOCATIONS.length + ELIGIBILITY_POLICIES.length + APPLICATIONS.length + DECISIONS.length + DISBURSEMENTS.length} public fund records to ${ROOT_DID}`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

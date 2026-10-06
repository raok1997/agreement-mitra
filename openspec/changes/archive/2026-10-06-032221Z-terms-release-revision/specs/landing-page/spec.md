## MODIFIED Requirements

### Requirement: The price card states the published fee
The home page SHALL show the price as a slim band (`#price`) directly under the hero, whose headline leads with its condition, as in "₹499 when your stamp is ₹100 or less". The price follows the **stamp value** on the agreement (the value of the stamp we buy), not the legal duty, because that is what `payment-processing` charges. The band states:
- a total of ₹499 that includes a stamp of up to ₹100;
- that where the stamp duty is more than ₹100, the stamp we can buy, the duty and the exact total are shown before payment, and there is no second bill (the band keeps the phrase "stamp duty", which it owns under "Each recurring message has one owning section");
- that drafting, previewing and downloading a draft cost nothing;
- that the price applies where stamping and eSign are offered, pointing to the status board without itself stating what is live.

The rupee figures SHALL come from a single frontend constant, which SHALL equal the backend's default fee configuration and the figures in ToS §7. A price statement is not an availability claim.

#### Scenario: The band shows the price, the overflow rule and its scope
- **GIVEN** the home page is rendered
- **WHEN** the `#price` section is read
- **THEN** it contains all of the following:
  - "₹499" and the included "₹100";
  - "exact total before you pay";
  - a statement that drafting is free;
  - a link to `#status`

#### Scenario: The constant matches the backend default fee
- **GIVEN** `application.yml` declares `payment.fee.base-minor-units` and `payment.fee.included-stamp-value-minor-units` defaults
- **WHEN** the pricing constant is compared with those defaults divided by 100
- **THEN** both the total and the included stamp value are equal

#### Scenario: The constant matches the published terms
- **GIVEN** the ToS clause with id `our-fee` (§7), looked up by id
- **WHEN** its body is searched for "INR <total> where the stamp value on your agreement is INR <included stamp value> or less", built from the constant
- **THEN** it is found

### Requirement: Guarantees mirror the terms of service
The home page SHALL present the guarantees as the `#guarantees` tab panel of the "Before you decide" panel. It SHALL hold exactly three guarantees, each restating one ToS promise with all its qualifying conditions and linking to `/terms`:
- a wrong stamp that is our fault is fixed at our cost, plus a ₹400 refund (§8);
- a failed or expired signing request is re-sent at no charge (§11);
- a delay of more than two working days through our fault earns ₹100 per further working day, up to ₹400 (§14).

Each guarantee SHALL show its title as a one-line headline that expands to its body, its qualifier and its "Terms, section N" link. The collapsed body, qualifier and link SHALL stay in the DOM.

The panel SHALL NOT promise more than those clauses. Its rupee figures and day counts SHALL come from one frontend constant, and that constant SHALL be checked against the ToS clause bodies.

#### Scenario: Three guarantees, each expandable and linked to the terms
- **GIVEN** the home page is rendered
- **WHEN** the `#guarantees` panel is read
- **THEN** it contains three `details` elements marked `data-testid="guarantee"`, each with its title in the `summary` and a link `href="/terms"` in its body:
  - a stamp guarantee naming ₹400;
  - a signing guarantee naming a re-send "at no charge";
  - a delay guarantee naming "₹100 for each further working day, up to ₹400".

#### Scenario: Each guarantee keeps its qualifiers
- **GIVEN** the `#guarantees` panel
- **WHEN** each guarantee's text, including its collapsed body, is read
- **THEN** the guarantees carry these qualifiers:
  - stamp: the mistake must be ours, and the refund is reduced by any discount;
  - signing: repeated signer-side failures may cost ₹100;
  - delay: it names the one-working-day stamping target and that out-of-hours orders count from the next working day; it applies only beyond two working days late through our fault; it excludes waiting on the customer's details, an unreachable signer, SHCIL or eSign being down or no licensed vendor being able to supply the stamp and public holidays; and it is reduced by any discount.

#### Scenario: Guarantee figures match the terms
- **GIVEN** the guarantees constant
- **WHEN** the ToS clauses with ids `stamp-duty` (§8), `refunds` (§11) and `availability-and-support` (§14), looked up by id, are searched for clause-local phrases built from the constant: "refund you INR <n>" (§8), "ask for INR <n> before starting it again" (§11), and for §14 "within <word> working day of payment", "more than <word> working days late", "INR <n> for each further working day, up to INR <cap>" and the support hours
- **THEN** every phrase is found in its clause

#### Scenario: The old pillar copy is gone
- **GIVEN** the home page is rendered
- **WHEN** its text is searched for "Why bother building another one of these" and "in your language"
- **THEN** neither is found

## ADDED Requirements

### Requirement: The home page describes the stamp without assuming its medium
The home page and its FAQPage JSON-LD SHALL describe the stamp in words that hold for every state served: a Karnataka SHCIL e-stamp certificate and Telangana non-judicial stamp paper from a licensed vendor. They SHALL NOT call every stamp a "certificate" or say a stamp is bought on a portal. The eSign answer's "digital signature certificate" is about the signature, not the stamp, and is unaffected.

#### Scenario: The home page does not assume the stamp medium
- **GIVEN** the home page is rendered, together with the FAQPage JSON-LD in `index.html`
- **WHEN** their text is searched case-insensitively for "portal" and for every occurrence of "certificate"
- **THEN** "portal" is not found
- **AND** every occurrence of "certificate" is part of "e-stamp certificate" or "digital signature certificate"

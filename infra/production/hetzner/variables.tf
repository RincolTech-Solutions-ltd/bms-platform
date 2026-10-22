# Nothing that costs money or decides where data lives has a default: location, server_type, r2_location and
# the Cloudflare account are the owner's decisions (docs/runbooks/terraform-production.md, "Decide first").

variable "location" {
  description = "Hetzner location (fsn1, nbg1, hel1, ash, hil, sin). No default: the processing region is a data protection decision (Uganda PDPO, NFR-DP-07) recorded in an ADR before production data arrives."
  type        = string
}

variable "server_type" {
  description = "Hetzner server type. No default: the documented size is 2 vCPU, 4 GB RAM, 40 GB disk (chapter 9 section 9.4), which Hetzner sells as cx23 in some locations; check availability in the chosen location."
  type        = string
}

variable "cloudflare_account_id" {
  description = "The existing Cloudflare account that holds the zone. Set it as TF_VAR_cloudflare_account_id, never in a committed file."
  type        = string
}

variable "r2_location" {
  description = "R2 location hint for the backup and documents buckets (weur, eeur, apac, enam, wnam, oc). No default: where backups and documents are kept is part of the same data protection decision."
  type        = string
  validation {
    condition     = contains(["weur", "eeur", "apac", "enam", "wnam", "oc"], var.r2_location)
    error_message = "Use one of the R2 location hints: weur, eeur, apac, enam, wnam, oc."
  }
}

variable "ssh_public_keys" {
  description = "Public keys of the people allowed to sign in as ops. sshd refuses root and passwords (cloud-init)."
  type        = list(string)
  validation {
    condition     = length(var.ssh_public_keys) > 0
    error_message = "Give at least one SSH public key."
  }
}

variable "admin_cidrs" {
  description = "Addresses allowed to reach SSH (port 22), for example [\"203.0.113.7/32\"]. Nothing else is opened for SSH."
  type        = list(string)
  validation {
    condition     = length(var.admin_cidrs) > 0 && alltrue([for c in var.admin_cidrs : can(cidrhost(c, 0)) && !endswith(c, "/0")])
    error_message = "Give at least one address in CIDR form and never the whole internet."
  }
}

variable "name" {
  description = "Name of the server, its firewall, labels and the Cloudflare tokens."
  type        = string
  default     = "bms-prod"
}

variable "image" {
  description = "Operating system image. The 24.04 LTS on purpose: cloud-init installs Docker from Docker's own apt repository, which may not carry packages for a brand-new Ubuntu release yet."
  type        = string
  default     = "ubuntu-24.04"
}

variable "backups" {
  description = "Hetzner's own daily server backups (about a fifth of the server price), in addition to the encrypted off-site database backups of backup.sh, not instead of them."
  type        = bool
  default     = true
}

variable "enable_ipv6" {
  description = "Give the server a public IPv6 address and publish AAAA records."
  type        = bool
  default     = true
}

variable "domain" {
  description = "The DNS zone of the platform and tenant hosts, in the existing Cloudflare account."
  type        = string
  default     = "rincoltech.com"
}

variable "tenant_slugs" {
  description = "One DNS record <slug>-bms.<domain> per production tenant. Add a slug when a tenant goes live."
  type        = list(string)
  default     = []
}

variable "backup_bucket_name" {
  description = "R2 bucket for the encrypted database backups (R2_BACKUP_BUCKET)."
  type        = string
  default     = "bms-production-backups"
}

variable "documents_bucket_name" {
  description = "R2 bucket for the API's documents (R2_DOCUMENTS_BUCKET)."
  type        = string
  default     = "bms-production-documents"
}

variable "daily_retention_days" {
  description = "Lock window of daily backups (ADR-058)."
  type        = number
  default     = 30
}

variable "monthly_retention_days" {
  description = "Lock window of monthly backups (ADR-058)."
  type        = number
  default     = 365
}

variable "restrict_tokens_to_host" {
  description = "Make the host's Cloudflare tokens usable only from the server's addresses."
  type        = bool
  default     = true
}

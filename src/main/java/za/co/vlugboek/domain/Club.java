package za.co.vlugboek.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;
import jakarta.persistence.Table;

@Entity
@Table(name = "clubs")
public class Club {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "federation_id")
    private Federation federation;

    protected Club() {
    }

    public Club(String name, Federation federation) {
        update(name);
        this.federation = federation;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Federation getFederation() {
        return federation;
    }

    public void update(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Club name is required");
        }
        this.name = name.trim();
    }
}

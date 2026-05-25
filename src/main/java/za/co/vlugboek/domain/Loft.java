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
@Table(name = "lofts")
public class Loft {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "club_id")
    private Club club;

    protected Loft() {
    }

    public Loft(String name, Club club) {
        update(name);
        this.club = club;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Club getClub() {
        return club;
    }

    public void update(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Loft name is required");
        }
        this.name = name.trim();
    }
}
